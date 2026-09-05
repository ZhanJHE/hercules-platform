package com.zhanjh.hercules.cache.remote;

import java.time.Duration;

/**
 * 空操作回源锁：分布式锁关闭（hercules.cache.load-lock.enabled=false）或无 Redis 环境时的实现。
 *
 * <p>语义约定：{@link #tryLock(String, Duration)} <b>恒返回 true（视为总是抢到）</b>——
 * 使读路径直接进入「双检 + loader 回源」分支，等价于无分布式锁的单机行为；
 * {@link #unlock(String)} 为无操作。进程内的并发合并由 SingleFlight 保证。
 *
 * <p>线程安全性：无可变状态，可并发调用。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public class NoopCacheLoadLock implements CacheLoadLock {

    /**
     * 实现：恒返回 true（总是抢到），使读路径直接回源。
     */
    @Override
    public boolean tryLock(String key, Duration ttl) {
        return true;
    }

    /**
     * 实现：无操作。
     */
    @Override
    public void unlock(String key) {
        // 无分布式锁：无操作
    }
}
