package com.zhanjh.hercules.cache.local;

import java.time.Duration;

/**
 * 本地缓存（Caffeine）抽象（设计类：LocalCacheManager），MVP 中作为单机进程内的 L1 层。
 *
 * <p>职责：定义 L1 的最小操作集——读、写、失效、容量估算；实现类为
 * {@link CaffeineLocalCacheManager}，作为 {@link com.zhanjh.hercules.cache.core.MultiLevelCacheManager}
 * 读路径的第一级。
 *
 * <p>线程安全性：实现需线程安全（Caffeine 原生线程安全），可并发调用。
 *
 * <p>扩展点：put 的 ttl 参数为逐键 TTL——Caffeine 实现已通过自定义 Expiry 支持，
 * 与全局默认不同的 TTL（如列表键 10s）按覆盖值过期；后续自适应 TTL 策略无需改动本接口。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public interface LocalCacheManager {

    /**
     * 读取 L1 缓存。
     *
     * @param key 缓存键（如 {@code course:{id}}），不允许为 null
     * @return 命中时返回 JSON 字符串；未命中或已过期时返回 null
     */
    String get(String key);

    /**
     * 写入 L1 缓存。
     *
     * @param key   缓存键，不允许为 null
     * @param value JSON 字符串形式的缓存值，不允许为 null
     * @param ttl   期望过期时长；与全局默认（hercules.cache.local-ttl）不同时按该值逐键过期，null/非正表示使用全局默认
     */
    void put(String key, String value, Duration ttl);

    /**
     * 立即失效指定键（从 L1 删除，键不存在时为无害操作）。
     *
     * @param key 缓存键，不允许为 null
     */
    void invalidate(String key);

    /**
     * 估算 L1 当前条目数（基于 Caffeine 内部计数，非精确值）。
     *
     * @return 条目数估计值，最小为 0
     */
    long estimatedSize();
}
