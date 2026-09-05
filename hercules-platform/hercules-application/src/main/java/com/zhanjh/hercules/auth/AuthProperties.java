package com.zhanjh.hercules.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 认证配置（hercules.auth.*）。
 *
 * <p>secret 来自 application-local.yml（gitignore），HS256 要求 UTF-8 编码后 ≥ 32 字节
 * （256 位），由 JwtUtil 构造时校验，不满足直接拒绝启动。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@ConfigurationProperties(prefix = "hercules.auth")
public class AuthProperties {

    /** JWT 签名密钥（≥32 字节）；生产环境必须替换且 gitignore。 */
    private String secret;

    /** Access Token 有效期，默认 30 分钟。 */
    private Duration accessTtl = Duration.ofMinutes(30);

    /** Refresh Token 有效期，默认 7 天。 */
    private Duration refreshTtl = Duration.ofDays(7);

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    /**
     * 读取 Access Token 有效期。
     *
     * @return 当前值（hercules.auth.access-ttl），默认 30m
     */
    public Duration getAccessTtl() {
        return accessTtl;
    }

    /**
     * 设置 Access Token 有效期（Spring 绑定入口）。
     *
     * @param accessTtl 有效期，须为正值
     */
    public void setAccessTtl(Duration accessTtl) {
        this.accessTtl = accessTtl;
    }

    /**
     * 读取 Refresh Token 有效期。
     *
     * @return 当前值（hercules.auth.refresh-ttl），默认 7d
     */
    public Duration getRefreshTtl() {
        return refreshTtl;
    }

    /**
     * 设置 Refresh Token 有效期（Spring 绑定入口）。
     *
     * @param refreshTtl 有效期，须为正值
     */
    public void setRefreshTtl(Duration refreshTtl) {
        this.refreshTtl = refreshTtl;
    }
}
