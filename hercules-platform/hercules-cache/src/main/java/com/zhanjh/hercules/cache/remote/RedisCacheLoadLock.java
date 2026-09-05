package com.zhanjh.hercules.cache.remote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Redis 的分布式回源锁实现：SET NX PX 抢锁 + Lua 比对令牌删除（阶段 A+ 防线③）。
 *
 * <p>机制：
 * <ul>
 *   <li>tryLock：{@code SET lock:cache:load:{key} <uuid> NX PX <ttl>}，原子抢占；</li>
 *   <li>unlock：Lua 脚本「GET == 自身令牌才 DEL」，防止超时后误删其他持有者的锁；
 *       锁 TTL 到期后自动释放（防持有者宕机死锁），此时旧持有者的 unlock 为无害操作；</li>
 *   <li>容错约定：任何 Redis 异常 → tryLock 返回 false（按「未抢到」处理）、unlock 静默忽略，
 *       绝不影响读路径；JVM 内按 key 保存令牌（单飞已保证同键单线程，无需并发令牌表）。</li>
 * </ul>
 *
 * <p>线程安全性：令牌表为 ConcurrentHashMap；StringRedisTemplate 线程安全。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public class RedisCacheLoadLock implements CacheLoadLock {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheLoadLock.class);

    /** 锁键前缀：lock:cache:load:{缓存键}。 */
    private static final String KEY_PREFIX = "lock:cache:load:";

    /** 比对令牌删除脚本：GET == ARGV[1] 才 DEL，返回删除数。 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    /** 本 JVM 持有的锁令牌：lockKey → uuid（单飞保证同键单线程，无需并发结构之外的语义）。 */
    private final Map<String, String> tokens = new ConcurrentHashMap<>();

    public RedisCacheLoadLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 实现：SET NX PX 原子抢锁；任何异常按「未抢到」处理（返回 false，不抛出）。
     */
    @Override
    public boolean tryLock(String key, Duration ttl) {
        String lockKey = KEY_PREFIX + key;
        String token = UUID.randomUUID().toString();
        try {
            Boolean ok = redisTemplate.opsForValue().setIfAbsent(lockKey, token, ttl);
            if (Boolean.TRUE.equals(ok)) {
                tokens.put(lockKey, token);
                return true;
            }
            return false;
        } catch (Exception e) {
            // Redis 故障：按「未抢到」处理，读路径将走轮询/安全阀，绝不抛出
            log.warn("[hercules-cache] load-lock tryLock failed (treated as not-acquired), key={}", key, e);
            return false;
        }
    }

    /**
     * 实现：Lua 比对令牌删除，仅对自己持有的锁生效；任何异常静默忽略（锁 TTL 兜底）。
     */
    @Override
    public void unlock(String key) {
        String lockKey = KEY_PREFIX + key;
        String token = tokens.remove(lockKey);
        if (token == null) {
            return;
        }
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), token);
        } catch (Exception e) {
            // 释放失败静默忽略：锁 TTL 到期自动释放，不会死锁
            log.warn("[hercules-cache] load-lock unlock failed (lock will expire by TTL), key={}", key, e);
        }
    }
}
