package com.zhanjh.hercules.cache.core;

import com.zhanjh.hercules.cache.local.CaffeineLocalCacheManager;
import com.zhanjh.hercules.cache.local.LocalCacheManager;
import com.zhanjh.hercules.cache.remote.DistributedCacheManager;
import com.zhanjh.hercules.cache.stats.CacheStatsCollector;
import com.zhanjh.hercules.cache.strategy.TTLStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * 多级缓存实现（L1 Caffeine → L2 Redis → DB），cache-aside + 逐级回填（CacheManager 的门面模式实现）。
 *
 * <p>读路径（{@link #get(String, Supplier)}）五步：L1 命中计 l1Hit 直接返回；L1 未命中计 l1Miss 下探；
 * L2 命中计 l2Hit 并按 TTL 策略回填 L1；L2 未命中计 l2Miss；loader 回源成功计 dbLoad，
 * 先回填 L2（remoteTtl）再回填 L1（localTtl）。loader 返回 null 表示数据不存在：
 * 不计数、不回填、不缓存空值。命中率等指标由 {@link CacheStatsCollector} 累计，
 * 经 /api/v1/cache/stats 与 /actuator/prometheus 两个出口暴露。
 *
 * <p>写/失效路径与 hercules.sync 向量时钟同步链配合：put 先写 L2 再写 L1；
 * invalidate 先删 L2 再删本节点 L1，其他节点的 L1 失效由同步链消费端广播；
 * invalidateLocal 只删本节点 L1（演示「Redis 回填」）。
 *
 * <p>线程安全性：四个依赖字段均为 final、不可变；Caffeine 与 StringRedisTemplate 线程安全，
 * 可并发调用；并发未命中时可能重复回源（MVP 可接受，不加分布式锁防击穿）。
 *
 * <p>扩展点：remote 允许为 null（无 Redis/单测环境退化 L1+DB）；后续接入 Redisson
 * 可替换 remote 实现以增加分布式锁防击穿能力。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public class MultiLevelCacheManager implements CacheManager {

    /** SLF4J 日志：失效操作输出 debug 级键留痕。 */
    private static final Logger log = LoggerFactory.getLogger(MultiLevelCacheManager.class);

    /** L1 本地缓存（Caffeine 实现），必选依赖。 */
    private final LocalCacheManager local;
    /** L2 分布式缓存（Redis 实现）；允许为 null（仅 L1 模式，如单测），为 null 时读路径退化为 L1 → loader。 */
    private final DistributedCacheManager remote;
    /** TTL 策略（策略模式）：按键决定回填 L1/L2 时使用的过期时长。 */
    private final TTLStrategy ttlStrategy;
    /** 运行时计数器：累计 l1Hit/l1Miss/l2Hit/l2Miss/dbLoad。 */
    private final CacheStatsCollector stats;

    /**
     * 构造多级缓存管理器（由 CacheConfig 装配，依赖经构造注入）。
     *
     * @param local       L1 本地缓存实现，不允许为 null
     * @param remote      L2 分布式缓存实现，允许为 null（缺省时退化为 L1 + DB）
     * @param ttlStrategy TTL 策略，不允许为 null
     * @param stats       运行时计数器，不允许为 null
     */
    public MultiLevelCacheManager(LocalCacheManager local,
                                  DistributedCacheManager remote,
                                  TTLStrategy ttlStrategy,
                                  CacheStatsCollector stats) {
        this.local = local;
        this.remote = remote;
        this.ttlStrategy = ttlStrategy;
        this.stats = stats;
    }

    /**
     * 多级读取实现：L1 → L2 → loader 逐级下探，命中逐级回填，并在各级命中点计数。
     *
     * <p>实现要点：
     * <ol>
     *   <li>L1 命中：计 l1Hit 直接返回，不再访问 L2 与 DB；</li>
     *   <li>L1 未命中：计 l1Miss；remote 为 null 时跳过 L2 直接回源（单测退化 L1+DB）；</li>
     *   <li>L2 命中：计 l2Hit，按 localTtl 回填 L1 后返回；L2 未命中：计 l2Miss；</li>
     *   <li>loader 返回 null：数据不存在，不计数不回填，直接返回 null；</li>
     *   <li>loader 成功：计 dbLoad，先按 remoteTtl 回填 L2，再按 localTtl 回填 L1。</li>
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

        if (remote != null) { // remote 允许缺省（无 Redis/单测），缺省时跳过 L2
            value = remote.get(key);
            if (value != null) {
                stats.l2Hit();
                local.put(key, value, ttlStrategy.localTtl(key)); // L2 命中后回填 L1，加速本进程后续读取
                return value;
            }
            stats.l2Miss();
        }

        value = loader.get();
        if (value == null) {
            // 数据不存在：不计数、不回填、不缓存空值（MVP 不做穿透保护）
            return null;
        }
        stats.dbLoad();
        if (remote != null) {
            remote.put(key, value, ttlStrategy.remoteTtl(key)); // 先回填 L2，其他节点即可共享
        }
        local.put(key, value, ttlStrategy.localTtl(key)); // 再回填 L1
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
     * 跨节点 L1 失效由 hercules.sync 同步链消费端广播，本方法不负责。
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
