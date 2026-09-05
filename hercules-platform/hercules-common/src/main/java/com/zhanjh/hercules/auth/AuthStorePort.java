package com.zhanjh.hercules.auth;

import java.time.Duration;

/**
 * 认证存储端口（阶段 A+）：Refresh Token 与 jti 黑名单的持久化抽象。
 *
 * <p>生产实现为 Redis（RedisAuthStore），测试用内存桩（InMemoryAuthStore）。
 * 阶段 H 迁移网关后由网关侧实现同一语义。
 *
 * <p><b>故障语义约定</b>（对应《认证设计.md》§四）：
 * <ul>
 *   <li>{@link #isBlacklisted(String)}：<b>永不抛异常</b>——Redis 故障返回 false（fail-open，
 *       可用性优先：黑名单只影响「登出后 token 被重放」的低危场景），实现方自行告警；</li>
 *   <li>其余方法（refresh 读写、黑名单写入）：<b>允许抛异常</b>——调用方 fail-closed
 *       （刷新失败让用户重新登录；登出失败让用户重试，不允许「登出失败但 token 继续有效」）。</li>
 * </ul>
 *
 * @author zhanjh
 * @since 0.0.1
 */
public interface AuthStorePort {

    /**
     * 保存 Refresh Token（登录/旋转刷新时调用）。
     *
     * @param uuid  刷新令牌（无业务语义的 UUID）
     * @param userId 用户主键
     * @param ttl   有效期（默认 7d）
     */
    void saveRefreshToken(String uuid, long userId, Duration ttl);

    /**
     * 读取 Refresh Token 对应的用户主键。
     *
     * @param uuid 刷新令牌
     * @return 用户主键；不存在/已过期返回 null
     * @throws RuntimeException Redis 故障时抛出（调用方 fail-closed）
     */
    Long getRefreshUserId(String uuid);

    /**
     * 删除 Refresh Token（旋转刷新删旧值、登出吊销）。
     *
     * @param uuid 刷新令牌
     */
    void deleteRefreshToken(String uuid);

    /**
     * 将 Access Token 的 jti 写入黑名单（登出）。
     *
     * @param jti       JWT 唯一 ID
     * @param remaining 黑名单保留时长 = Access 剩余有效期
     * @throws RuntimeException Redis 故障时抛出（调用方返回 500 让用户重试）
     */
    void blacklistJti(String jti, Duration remaining);

    /**
     * 查询 jti 是否在黑名单（登出后的 token 拒绝使用）。
     *
     * @param jti JWT 唯一 ID
     * @return true=已列入黑名单（拒绝）；false=不在黑名单或查询失败（fail-open 放行）
     */
    boolean isBlacklisted(String jti);
}
