package com.zhanjh.hercules.cache.local;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.zhanjh.hercules.cache.config.HerculesCacheProperties;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caffeine 本地缓存实现：逐键 TTL（自定义 {@link Expiry}）+ 容量上限 + 统计开关（LocalCacheManager 的 L1 实现）。
 *
 * <p>核心机制：构建时挂载自定义 Expiry，实现「逐键过期」——put 时若传入的 ttl 与全局默认
 * 不同（如列表键的 10s 最终一致窗口）则登记到 ttlOverrides 覆盖表，过期计算按覆盖值；
 * 未覆盖的键按 hercules.cache.local-ttl（默认 60s）过期；容量上限
 * maximumSize = hercules.cache.local-max-size（默认 1000，超出按 W-TinyLFU 淘汰）。
 * expireAfterRead 返回剩余时长原值（currentDuration）：只在写入时计时、读访问不续期（cache-aside 语义）。
 * recordStats 开启的 Caffeine 内置统计当前无消费方，运行时命中率以门面层 CacheStatsCollector 为准。
 *
 * <p>线程安全性：Caffeine Cache 与 ttlOverrides（ConcurrentHashMap）均线程安全，可并发调用。
 *
 * <p>内存取舍：ttlOverrides 的键不会随条目过期自动清理。put 时覆盖表达到容量上限
 * （hercules.cache.ttl-override-cap，默认 4096）则**先回收死键**——对应条目已过期或被容量淘汰的
 * 覆盖项——仍超限才整体清空重建。剪枝优先保证仍活跃的覆盖项（如列表键的 10s）不被误清；
 * 仅极端兜底清空时，活跃列表键会短暂退回默认 TTL（60s），并随下一次写入重新登记。
 *
 * <p>扩展点：AdaptiveTTLStrategy / 容量自适应淘汰留待后续冲刺扩展。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public class CaffeineLocalCacheManager implements LocalCacheManager {

    /** 逐键 TTL 覆盖表容量上限（hercules.cache.ttl-override-cap）：达到上限先回收死键，仍超限才整体清空。 */
    private final int ttlOverrideCap;

    /** 底层 Caffeine 缓存：key/value 均为 String（value 为 JSON），过期策略为逐键 Expiry。 */
    private final Cache<String, String> cache;

    /** 逐键 TTL 覆盖表：key → 过期时长；与全局默认相同的键不入表。 */
    private final ConcurrentHashMap<String, Duration> ttlOverrides = new ConcurrentHashMap<>();

    /** 全局默认 TTL（hercules.cache.local-ttl，默认 60s），未覆盖键的过期时长。 */
    private final Duration defaultTtl;

    /**
     * 按配置构建 Caffeine 缓存：自定义 Expiry 实现逐键过期 + 容量上限 + 统计开关一次成型。
     *
     * @param props 缓存配置（hercules.cache.*）：localTtl 决定默认过期、localMaxSize 决定容量上限、
     *              ttlOverrideCap 决定覆盖表容量阈值
     */
    public CaffeineLocalCacheManager(HerculesCacheProperties props) {
        this.defaultTtl = props.getLocalTtl();
        this.ttlOverrideCap = props.getTtlOverrideCap();
        this.cache = Caffeine.newBuilder()
                .expireAfter(new Expiry<String, String>() {
                    @Override
                    public long expireAfterCreate(String key, String value, long currentTime) {
                        return ttlFor(key).toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, String value, long currentTime, long currentDuration) {
                        return ttlFor(key).toNanos();
                    }

                    @Override
                    public long expireAfterRead(String key, String value, long currentTime, long currentDuration) {
                        // 读访问不续期：返回剩余时长原值（若返回 Long.MAX_VALUE 会被 Caffeine
                        // 理解为「从现在起再活 MAX_VALUE」，读一次即永生——语义完全错误）
                        return currentDuration;
                    }
                })
                .maximumSize(props.getLocalMaxSize())
                .recordStats()
                .build();
    }

    /**
     * 解析某键的过期时长：优先取覆盖表登记值，缺省用全局默认。
     *
     * @param key 缓存键，不允许为 null
     * @return 过期时长（正值）
     */
    private Duration ttlFor(String key) {
        Duration ttl = ttlOverrides.get(key);
        return ttl == null ? defaultTtl : ttl;
    }

    /**
     * 读取实现：委托 Caffeine getIfPresent（读取时惰性判定过期，过期键返回 null）。
     *
     * @param key 缓存键，不允许为 null
     * @return 命中返回 JSON 字符串；未命中或已过该键 TTL 时返回 null
     */
    @Override
    public String get(String key) {
        return cache.getIfPresent(key);
    }

    /**
     * 写入实现：逐键 TTL——与全局默认不同的 ttl（如列表键 10s）登记覆盖表后写缓存，
     * 使 Expiry 按覆盖值计算过期；与全局默认相同或非法（null/非正）的 ttl 不登记，
     * 避免覆盖表无谓增长。覆盖登记必须先于 cache.put，保证 Expiry 回调能读到该键的 TTL。
     *
     * <p>覆盖表达到上限时的回收策略见类注释「内存取舍」：先剪除死键、仍超限才整体清空。
     *
     * @param key   缓存键，不允许为 null
     * @param value JSON 字符串形式的缓存值，不允许为 null
     * @param ttl   该键的期望过期时长；null/非正表示使用全局默认
     */
    @Override
    public void put(String key, String value, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.equals(defaultTtl)) {
            ttlOverrides.remove(key);
        } else {
            if (ttlOverrides.size() >= ttlOverrideCap) {
                // 先回收死键（对应条目已过期或被容量淘汰），避免整体清空误伤仍活跃的覆盖项
                // ——否则列表键会从 10s 最终一致窗口退回 60s 默认 TTL
                ttlOverrides.keySet().removeIf(k -> cache.getIfPresent(k) == null);
                if (ttlOverrides.size() >= ttlOverrideCap) {
                    // 仍超限才整体清空重建（极端兜底：活跃覆盖项在下次写入时重新登记）
                    ttlOverrides.clear();
                }
            }
            ttlOverrides.put(key, ttl);
        }
        cache.put(key, value);
    }

    /**
     * 失效实现：清除覆盖登记并从 L1 删除该键（键不存在时为无害操作）。
     *
     * @param key 缓存键，不允许为 null
     */
    @Override
    public void invalidate(String key) {
        ttlOverrides.remove(key);
        cache.invalidate(key);
    }

    /**
     * 容量估算实现：委托 Caffeine estimatedSize（基于内部计数的估计值，可能短暂滞后于真实条目数）。
     *
     * @return 条目数估计值
     */
    @Override
    public long estimatedSize() {
        return cache.estimatedSize();
    }
}
