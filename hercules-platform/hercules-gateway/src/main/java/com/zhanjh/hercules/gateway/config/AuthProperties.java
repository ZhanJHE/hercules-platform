package com.zhanjh.hercules.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 认证配置（hercules.auth.*，网关侧）：与后端共享同一 secret（经部署环境变量下发），
 * 用于 JWT 验签。TTL 仅作展示参考，过期校验以 JWT exp 为准。
 *
 * <p>注意：本类与 hercules-application 中的同名前缀配置类是各自模块的独立实现
 * （网关不依赖应用模块）。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@ConfigurationProperties(prefix = "hercules.auth")
public class AuthProperties {

    /** JWT 签名密钥（≥32 字节），与后端共享；生产环境经部署环境变量注入。 */
    private String secret;

    /** Access Token 有效期（仅用于响应展示参考），默认 30 分钟。 */
    private Duration accessTtl = Duration.ofMinutes(30);

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
}
