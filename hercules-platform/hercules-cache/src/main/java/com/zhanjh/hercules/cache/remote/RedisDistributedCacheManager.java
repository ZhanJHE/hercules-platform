package com.zhanjh.hercules.cache.remote;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 基于 StringRedisTemplate(Lettuce) 的 L2 实现：仅使用 GET / SET [EX] / DEL 基础命令。
 *
 * <p>核心机制：底层复用 Spring Data Redis 的 StringRedisTemplate（Lettuce 驱动），
 * 只用 GET、{@code SET key value [EX]}、DEL 三类命令，兼容较旧的 Redis 服务端版本。
 * TTL 三态规则：ttl 为 null、负数或零时执行不带 EX 的 SET（键永不过期），正值时附带过期时间写入；
 * 正常路径由 FixedTTLStrategy 保证传入正值，不会产生永生键。
 *
 * <p>线程安全性：StringRedisTemplate 线程安全，本类无可变状态，可并发调用。
 *
 * <p>扩展点：Redisson（分布式锁/RMap）留待 Sprint 2+；可按需补充 mget/pipeline 批量命令。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Component
public class RedisDistributedCacheManager implements DistributedCacheManager {

    /** Spring Data Redis 字符串模板（Lettuce 驱动），线程安全，仅执行 String 级基础命令。 */
    private final StringRedisTemplate redisTemplate;

    /**
     * 注入 Redis 模板（由 Spring Boot Redis 自动配置提供 Bean；Lettuce 首次使用时才建立连接）。
     *
     * @param redisTemplate StringRedisTemplate 实例，不允许为 null
     */
    public RedisDistributedCacheManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 读取实现：GET key。
     *
     * @param key 缓存键，不允许为 null
     * @return 键存在且未过期时返回 JSON 字符串；键不存在或已过期时返回 null
     */
    @Override
    public String get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 写入实现：按 TTL 三态执行 SET——null/负数/零：不带 EX 的 SET（永不过期）；
     * 正值：写入并附带过期时间（SET key value EX，精度由 Spring Data Redis 换算）。
     *
     * @param key   缓存键，不允许为 null
     * @param value JSON 字符串形式的缓存值，不允许为 null
     * @param ttl   过期时长；null 或非正视为不过期
     */
    @Override
    public void put(String key, String value, Duration ttl) {
        // null/负/零 TTL 走无 EX 的 SET：正常路径由 TTLStrategy 保证正值，永生键仅出现在异常传参下
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            redisTemplate.opsForValue().set(key, value);
        } else {
            redisTemplate.opsForValue().set(key, value, ttl);
        }
    }

    /**
     * 删除实现：DEL key，键不存在时无副作用。
     *
     * @param key 缓存键，不允许为 null
     */
    @Override
    public void delete(String key) {
        redisTemplate.delete(key);
    }
}
