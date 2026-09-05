package com.zhanjh.hercules.testsupport;

import com.zhanjh.hercules.auth.AuthStorePort;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用认证存储桩（AuthStorePort 的内存实现）：替代 Redis，无外部依赖。
 *
 * <p>语义与 Redis 实现一致：
 * <ul>
 *   <li>refresh：uuid → userId 的 Map 存取；</li>
 *   <li>黑名单：jti → 过期时间戳，过期自动视为不在黑名单；</li>
 *   <li>故障语义：内存实现不会故障，故障分支由单元测试另行覆盖。</li>
 * </ul>
 *
 * <p>线程安全性：ConcurrentHashMap，可并发调用。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public class InMemoryAuthStore implements AuthStorePort {

    private final Map<String, Long> refreshTokens = new ConcurrentHashMap<>();
    private final Map<String, Long> blacklist = new ConcurrentHashMap<>();

    @Override
    public void saveRefreshToken(String uuid, long userId, Duration ttl) {
        refreshTokens.put(uuid, userId);
    }

    @Override
    public Long getRefreshUserId(String uuid) {
        return refreshTokens.get(uuid);
    }

    @Override
    public void deleteRefreshToken(String uuid) {
        refreshTokens.remove(uuid);
    }

    @Override
    public void blacklistJti(String jti, Duration remaining) {
        blacklist.put(jti, System.currentTimeMillis() + remaining.toMillis());
    }

    @Override
    public boolean isBlacklisted(String jti) {
        Long expireAt = blacklist.get(jti);
        if (expireAt == null) {
            return false;
        }
        if (expireAt < System.currentTimeMillis()) {
            blacklist.remove(jti); // 过期自动清理
            return false;
        }
        return true;
    }
}
