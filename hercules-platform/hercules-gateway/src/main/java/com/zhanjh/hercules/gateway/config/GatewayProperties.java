package com.zhanjh.hercules.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 网关配置（hercules.gateway.*）。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@ConfigurationProperties(prefix = "hercules.gateway")
public class GatewayProperties {

    /** 无需认证的路径白名单（Ant 风格），默认登录/刷新。 */
    private List<String> whitelist = List.of(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh");

    /**
     * 读取白名单。
     *
     * @return 白名单路径列表（Ant 风格）
     */
    public List<String> getWhitelist() {
        return whitelist;
    }

    /**
     * 设置白名单（Spring 绑定入口）。
     *
     * @param whitelist 白名单路径列表
     */
    public void setWhitelist(List<String> whitelist) {
        this.whitelist = whitelist;
    }
}
