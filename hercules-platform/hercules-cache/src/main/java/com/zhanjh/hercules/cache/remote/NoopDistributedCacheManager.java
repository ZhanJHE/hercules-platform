package com.zhanjh.hercules.cache.remote;

import java.time.Duration;

/**
 * 空操作 L2 实现：无 Redis 环境（未配置连接/单测上下文）时的占位实现。
 *
 * <p>语义约定：
 * <ul>
 *   <li>{@link #get(String)} 恒返回 null —— 等价于永远未命中，读路径退化为 L1 + DB；</li>
 *   <li>{@link #put(String, String, Duration)} / {@link #delete(String)} 为无操作；</li>
 *   <li>{@link #isAvailable()} 恒为 false —— 通知读路径跳过 L2 相关逻辑
 *       （含分布式回源锁与等待轮询），避免无意义的等待预算消耗。</li>
 * </ul>
 *
 * <p>线程安全性：无可变状态，可并发调用。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public class NoopDistributedCacheManager implements DistributedCacheManager {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String get(String key) {
        return null;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        // 无 Redis 环境：写操作为无操作
    }

    @Override
    public void delete(String key) {
        // 无 Redis 环境：删除为无操作
    }
}
