package com.zhanjh.hercules.cache.remote;

import java.time.Duration;

/**
 * 分布式回源锁（阶段 A+ 防线③）：缓存击穿的跨实例防护。
 *
 * <p>与进程内单飞（SingleFlight）是两级关系：单飞保证「本 JVM 内每键一个回源线程」；
 * 本锁保证「集群内每键一个回源实例」——抢到锁的实例回源并回填 L2，其余实例轮询 L2
 * 等待结果，超时后走安全阀自行回源。
 *
 * <p>实现约定：
 * <ul>
 *   <li>实现<b>不得向调用方抛出任何异常</b>——锁操作失败（含 Redis 故障）一律按
 *       「未抢到锁」或「静默忽略」处理，绝不能影响读路径；</li>
 *   <li>调用方必须在抢到锁后于 finally 中调用 {@link #unlock(String)}；</li>
 *   <li>锁自带 TTL（防持有者宕机死锁），unlock 用「比对令牌删除」防止误删他人的锁。</li>
 * </ul>
 *
 * @author zhanjh
 * @since 0.0.1
 */
public interface CacheLoadLock {

    /**
     * 尝试获取指定键的回源锁（非阻塞）。
     *
     * @param key 回源键（缓存键，实现方自行加锁前缀）
     * @param ttl 锁自动过期时长（防死锁）
     * @return true=抢到（调用方须在 finally 中 unlock）；false=未抢到（其他实例正在回源）
     */
    boolean tryLock(String key, Duration ttl);

    /**
     * 释放回源锁（仅对持有者生效；未持有时为无害操作）。
     *
     * @param key 回源键
     */
    void unlock(String key);
}
