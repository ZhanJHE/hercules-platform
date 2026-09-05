package com.zhanjh.hercules.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 认证存储的 Redis 实现（AuthStorePort）。
 *
 * <p>键设计（《认证设计.md》）：
 * <ul>
 *   <li>{@code auth:refresh:{uuid}} → userId，TTL 7d；</li>
 *   <li>{@code auth:blacklist:{jti}} → 1，TTL = Access 剩余有效期。</li>
 * </ul>
 *
 * <p>故障语义严格遵循端口约定：isBlacklisted 故障 fail-open（返回 false + 告警）；
 * refresh/黑名单写入故障向上抛出（调用方 fail-closed）。
 *
 * <p>线程安全性：StringRedisTemplate 线程安全，可并发调用。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public class RedisAuthStore implements AuthStorePort {

    private static final Logger log = LoggerFactory.getLogger(RedisAuthStore.class);

    private static final String REFRESH_PREFIX = "auth:refresh:";
    private static final String BLACKLIST_PREFIX = "auth:blacklist:";

    private final StringRedisTemplate redisTemplate;

    public RedisAuthStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 实现：SET auth:refresh:{uuid} userId EX ttl。
     */
    @Override
    public void saveRefreshToken(String uuid, long userId, Duration ttl) {
        redisTemplate.opsForValue().set(REFRESH_PREFIX + uuid, String.valueOf(userId), ttl);
    }

    /**
     * 实现：GET auth:refresh:{uuid}；Redis 故障时异常向上传播（刷新接口 fail-closed）。
     */
    @Override
    public Long getRefreshUserId(String uuid) {
        String value = redisTemplate.opsForValue().get(REFRESH_PREFIX + uuid);
        return value == null ? null : Long.parseLong(value);
    }

    /**
     * 实现：DEL auth:refresh:{uuid}（旋转刷新删旧值、登出吊销）。
     */
    @Override
    public void deleteRefreshToken(String uuid) {
        redisTemplate.delete(REFRESH_PREFIX + uuid);
    }

    /**
     * 实现：SET auth:blacklist:{jti} 1 EX 剩余有效期；Redis 故障时异常向上传播
     * （登出接口返回 500 让用户重试，不允许登出失败但 token 继续有效）。
     */
    @Override
    public void blacklistJti(String jti, Duration remaining) {
        redisTemplate.opsForValue().set(BLACKLIST_PREFIX + jti, "1", remaining);
    }

    /**
     * 实现：GET auth:blacklist:{jti}；<b>任何异常 fail-open 返回 false</b> + 告警日志
     * （可用性优先：黑名单仅影响「登出后 token 被重放」的低危场景）。
     */
    @Override
    public boolean isBlacklisted(String jti) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + jti));
        } catch (Exception e) {
            log.warn("[hercules-auth] blacklist check failed (fail-open), jti={}", jti, e);
            return false;
        }
    }
}
