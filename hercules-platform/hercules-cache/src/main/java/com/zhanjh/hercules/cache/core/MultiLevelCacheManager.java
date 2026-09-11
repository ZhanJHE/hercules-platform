package com.zhanjh.hercules.cache.core;

import com.zhanjh.hercules.cache.config.HerculesCacheProperties;
import com.zhanjh.hercules.cache.local.CaffeineLocalCacheManager;
import com.zhanjh.hercules.cache.local.LocalCacheManager;
import com.zhanjh.hercules.cache.remote.CacheLoadLock;
import com.zhanjh.hercules.cache.remote.DistributedCacheManager;
import com.zhanjh.hercules.cache.stats.CacheStatsCollector;
import com.zhanjh.hercules.cache.strategy.TTLStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * 多级缓存实现（L1 Caffeine → L2 Redis → DB），cache-aside + 逐级回填 + 高可用防护
 * （CacheManager 的门面模式实现，阶段 A+ 高可用加固）。
 *
 * <p>读路径（{@link #get(String, Supplier)}）：
 * <ol>
 *   <li>L1 命中计 l1Hit 直接返回；L1 未命中计 l1Miss 下探；</li>
 *   <li>L2 可用（remote 存在且 isAvailable）时探测：命中计 l2Hit 并回填 L1；
 *       未命中计 l2Miss；L2 不可用（熔断 OPEN/无 Redis）整体跳过本步；</li>
 *   <li>未命中 → <b>进程内单飞</b>（SingleFlight）：同键同一时刻仅一个队长线程回源，
 *       其余线程等待结果，防热点键过期击穿；</li>
 *   <li>队长路径先<b>双检</b>（等待期间键可能已被回填）→ 再尝试<b>分布式回源锁</b>
 *       （CacheLoadLock，跨实例防击穿）：抢到 → 回源并回填两级 → 释放；
 *       未抢到 → 按预算轮询 L2 等其他实例写回，超时走<b>安全阀</b>自行回源；
 *       Redis 不可用/锁关闭时跳过锁直接回源。</li>
 * </ol>
 * loader 返回 null 表示数据不存在：不计数、不回填、不缓存空值。
 *
 * <p>统计口径：l2Hit/l2Miss 只在外层探测计一次；单飞内部双检/锁内命中不再重复计数
 * （避免同一请求重复计入），命中率分母保持「进入读路径的请求数」。
 *
 * <p>写/失效路径与 hercules.sync 向量时钟同步链配合：put 先写 L2 再写 L1；
 * invalidate 先删 L2 再删本节点 L1（跨节点 L1 失效依赖同步链：canal-mq 传输下逐节点失效，
 * in-process 传输的事件不出本 JVM）；invalidateLocal 只删本节点 L1。
 * L2 经熔断装饰器保护：故障期间 put/delete 降级跳过、get 快速返回 null，
 * 本类通过 {@link DistributedCacheManager#isAvailable()} 感知并跳过 L2 相关等待。
 *
 * <p>线程安全性：依赖字段均为 final；Caffeine/StringRedisTemplate/SingleFlight/锁实现线程安全；
 * 同键并发未命中由单飞合并，跨实例由分布式锁合并。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public class MultiLevelCacheManager implements CacheManager {

    /** SLF4J 日志：失效操作输出 debug 级键留痕，安全阀触发输出 warn。 */
    private static final Logger log = LoggerFactory.getLogger(MultiLevelCacheManager.class);

    /** L1 本地缓存（Caffeine 实现），必选依赖。 */
    private final LocalCacheManager local;
    /** L2 分布式缓存（熔断装饰器包装的 Redis 实现）；允许为 null（仅 L1 模式，如单测）。 */
    private final DistributedCacheManager remote;
    /** TTL 策略（策略模式）：按键决定回填 L1/L2 时使用的过期时长。 */
    private final TTLStrategy ttlStrategy;
    /** 运行时计数器：累计 l1Hit/l1Miss/l2Hit/l2Miss/dbLoad。 */
    private final CacheStatsCollector stats;
    /** 进程内单飞：同键并发回源合并（防线③第一级）。 */
    private final SingleFlight singleFlight;
    /** 分布式回源锁：跨实例回源合并（防线③第二级）。 */
    private final CacheLoadLock loadLock;
    /** 缓存配置：回源锁开关、锁 TTL、轮询间隔与预算。 */
    private final HerculesCacheProperties props;

    /**
     * 构造多级缓存管理器（由 CacheConfig 装配，依赖经构造注入）。
     *
     * @param local        L1 本地缓存实现，不允许为 null
     * @param remote       L2 分布式缓存实现，允许为 null（缺省时退化为 L1 + DB）
     * @param ttlStrategy  TTL 策略，不允许为 null
     * @param stats        运行时计数器，不允许为 null
     * @param singleFlight 进程内单飞，不允许为 null
     * @param loadLock     分布式回源锁，不允许为 null（关闭/无 Redis 时为 Noop 实现）
     * @param props        缓存配置，不允许为 null
     */
    public MultiLevelCacheManager(LocalCacheManager local,
                                  DistributedCacheManager remote,
                                  TTLStrategy ttlStrategy,
                                  CacheStatsCollector stats,
                                  SingleFlight singleFlight,
                                  CacheLoadLock loadLock,
                                  HerculesCacheProperties props) {
        this.local = local;
        this.remote = remote;
        this.ttlStrategy = ttlStrategy;
        this.stats = stats;
        this.singleFlight = singleFlight;
        this.loadLock = loadLock;
        this.props = props;
    }

    /**
     * 多级读取实现：L1 → L2 → 单飞{ 双检 → 分布式锁 → loader } 逐级下探，命中逐级回填。
     *
     * <p>实现要点：
     * <ol>
     *   <li>L1 命中：计 l1Hit 直接返回；</li>
     *   <li>L2 探测仅当 remote 存在且 {@code isAvailable()}（熔断 OPEN 时跳过，快速落到回源）；</li>
     *   <li>未命中经 SingleFlight 合并：同键同一时刻仅一个队长执行 loadThrough；</li>
     *   <li>loadThrough 内先双检（等待期间可能已被回填），再走分布式锁/安全阀；</li>
     *   <li>loader 返回 null：数据不存在，不计数不回填，直接返回 null。</li>
     * </ol>
     *
     * @param key    缓存键，不允许为 null
     * @param loader 回源函数，返回 null 表示 DB 无数据
     * @return L1/L2/loader 中最先命中的 JSON 字符串；全部未命中时返回 null
     */
    @Override
    public String get(String key, Supplier<String> loader) {
        String value = local.get(key);
        if (value != null) {
            stats.l1Hit(); // L1 命中即返回，不再下探 L2/DB
            return value;
        }
        stats.l1Miss();

        // L2 探测仅在 remote 存在且可用时进行（熔断 OPEN / 无 Redis 时跳过，避免无谓的降级计数与调用）
        if (remote != null && remote.isAvailable()) {
            value = remote.get(key);
            if (value != null) {
                stats.l2Hit();
                local.put(key, value, ttlStrategy.localTtl(key)); // L2 命中后回填 L1
                return value;
            }
            stats.l2Miss();
        }

        // 未命中 → 进程内单飞：同键同一时刻仅一个队长线程回源（防线③第一级，防击穿）
        return singleFlight.run(key, () -> loadThrough(key, loader));
    }

    /**
     * 单飞队长的回源路径：双检 → 分布式回源锁/轮询 → loader 安全阀。
     *
     * <p>实现要点：
     * <ol>
     *   <li>双检 L1/L2：等待单飞期间键可能已被同进程其他线程或同步链回填；</li>
     *   <li>分布式锁（Redis 可用且开关开启）：抢到 → 锁内再查一次 L2 → loader → 回填两级；
     *       未抢到 → 按预算轮询 L2 等其他实例写回，超时走安全阀；</li>
     *   <li>Redis 不可用/锁关闭：跳过锁直接 loader（延迟上界 = 语句超时 3s）；</li>
     *   <li>loader 返回 null：不缓存不回填。</li>
     * </ol>
     *
     * @param key    缓存键，不允许为 null
     * @param loader 回源函数
     * @return 命中的 JSON 字符串；数据不存在时返回 null
     */
    private String loadThrough(String key, Supplier<String> loader) {
        // 双检 1：L1（等待单飞期间可能已被同进程其他请求回填）
        String value = local.get(key);
        if (value != null) {
            return value;
        }
        // 双检 2：L2（可用性现场重算：等待期间熔断器可能已迁移状态）
        boolean remoteUp = remote != null && remote.isAvailable();
        if (remoteUp) {
            value = remote.get(key);
            if (value != null) {
                local.put(key, value, ttlStrategy.localTtl(key));
                return value;
            }
        }

        // 分布式回源锁：仅 Redis 可用且开关开启（防线③第二级，跨实例防击穿）
        if (remoteUp && props.isLoadLockEnabled()) {
            if (loadLock.tryLock(key, props.getLoadLockTtl())) {
                try {
                    // 锁内再查一次 L2：其他实例可能在我们抢锁期间已完成回源
                    value = remote.get(key);
                    if (value == null) {
                        value = loader.get();
                        if (value == null) {
                            return null; // 数据不存在：不缓存空值
                        }
                        stats.dbLoad();
                        remote.put(key, value, ttlStrategy.remoteTtl(key)); // 先回填 L2，其他节点共享
                    }
                    local.put(key, value, ttlStrategy.localTtl(key)); // 再回填 L1
                    return value;
                } finally {
                    loadLock.unlock(key);
                }
            }
            // 未抢到锁：按预算轮询 L2，等待其他实例的队长写回
            long budget = props.getLoadLockPollBudget().toMillis();
            long interval = Math.max(1L, props.getLoadLockPollInterval().toMillis());
            long deadline = System.currentTimeMillis() + budget;
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                value = remote.get(key);
                if (value != null) {
                    local.put(key, value, ttlStrategy.localTtl(key));
                    return value;
                }
            }
            // 安全阀：预算内未等到（锁持有者失败/极慢）→ 记录后自行回源，不允许无限等待
            log.warn("[hercules-cache] load-lock wait budget exhausted, fallback to direct load, key={}", key);
        }

        // 直接回源（无 Redis / 锁关闭 / 锁等待超时的安全阀）
        value = loader.get();
        if (value == null) {
            return null;
        }
        stats.dbLoad();
        if (remoteUp) {
            remote.put(key, value, ttlStrategy.remoteTtl(key));
        }
        local.put(key, value, ttlStrategy.localTtl(key));
        return value;
    }

    /**
     * 写入实现：先写 L2（remoteTtl）再写 L1（localTtl），保证本进程与其他节点都能读到新值；
     * remote 缺省时仅写 L1。
     *
     * @param key   缓存键，不允许为 null
     * @param value JSON 字符串形式的缓存值，不允许为 null
     */
    @Override
    public void put(String key, String value) {
        if (remote != null) {
            remote.put(key, value, ttlStrategy.remoteTtl(key)); // 先远后近：L2 先落，跨节点可见
        }
        local.put(key, value, ttlStrategy.localTtl(key));
    }

    /**
     * 失效实现：先删 L2 再删本节点 L1，并输出 debug 日志留痕；
     * 跨节点 L1 失效由 hercules.sync 同步链消费端广播（canal-mq 传输下跨节点生效；
     * in-process 传输的事件不出本 JVM），本方法不负责。
     * L2 经熔断装饰器：故障期间删除降级跳过（残留旧值存活上界 = 剩余 TTL，见装饰器说明）。
     *
     * @param key 待失效的缓存键，不允许为 null
     */
    @Override
    public void invalidate(String key) {
        if (remote != null) {
            remote.delete(key);
        }
        local.invalidate(key);
        log.debug("[hercules-cache] invalidated key={}", key);
    }

    /**
     * 仅失效本节点 L1 实现：L2 保留，下次读取从 L2 命中并回填 L1（演示「Redis 回填」）。
     *
     * @param key 待失效的缓存键，不允许为 null
     */
    @Override
    public void invalidateLocal(String key) {
        local.invalidate(key);
    }

    /**
     * 查询本节点 L1 条目数（Caffeine estimatedSize 估计值，供 /api/v1/cache/stats 展示）。
     *
     * @return L1 当前缓存条目的估计值，非精确值
     */
    public long localSize() {
        return local.estimatedSize();
    }
}
