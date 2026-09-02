package com.zhanjh.hercules.cache.remote;

import java.time.Duration;

/**
 * 分布式缓存（Redis L2）抽象（设计类：DistributedCacheManager），跨节点共享的第二级缓存。
 *
 * <p>职责：定义 L2 的最小命令集——GET、SET（带 TTL）、DEL。MVP 用 Lettuce
 * （StringRedisTemplate）实现基础命令；Redisson（分布式锁/RMap）留待 Sprint 2+。
 *
 * <p>装配契约：实现 Bean 可缺省——CacheConfig 经 ObjectProvider 注入，容器中无实现时
 * 多级缓存退化为 L1 + DB（无 Redis/单测环境）。
 *
 * <p>线程安全性：实现需线程安全（StringRedisTemplate 线程安全），可并发调用。
 *
 * <p>扩展点：新增实现（如 Redisson 版）实现本接口替换即可；后续可增加 mget/pipeline 批量能力。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public interface DistributedCacheManager {

    /**
     * 读取 L2 缓存。
     *
     * @param key 缓存键，不允许为 null
     * @return 命中时返回 JSON 字符串；未命中、已过期或 Redis 不可用时返回 null
     */
    String get(String key);

    /**
     * 写入 L2 缓存。
     *
     * @param key   缓存键，不允许为 null
     * @param value JSON 字符串形式的缓存值，不允许为 null
     * @param ttl   过期时长；为 null 或非正值时写入的键不过期（三态约定见 Redis 实现）
     */
    void put(String key, String value, Duration ttl);

    /**
     * 删除 L2 缓存指定键（键不存在时无副作用）。
     *
     * @param key 缓存键，不允许为 null
     */
    void delete(String key);
}
