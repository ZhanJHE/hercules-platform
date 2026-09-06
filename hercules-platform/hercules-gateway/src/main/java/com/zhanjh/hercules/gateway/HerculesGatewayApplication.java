package com.zhanjh.hercules.gateway;

import com.zhanjh.hercules.auth.JwtUtil;
import com.zhanjh.hercules.gateway.config.AuthProperties;
import com.zhanjh.hercules.gateway.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * API 网关启动类（独立进程，WebFlux/Reactor 技术栈，阶段 H）。
 *
 * <p>职责（最小实现）：路由 /api/** 到 hercules-application；JWT 验签 + 过期校验；
 * 白名单放行；校验通过后剥离外部伪造的 X-Auth-* 头并注入可信透传头。
 * 黑名单查询与限流留待后续完善（应用层已有黑名单兜底）。
 *
 * <p>与后端的关系：共享 JWT 密钥（hercules.auth.secret，经部署环境变量下发）；
 * 后端保留自身 JWT 过滤器形成双层校验（防御纵深），透传头供未来多服务扩展。
 *
 * <p>组件扫描说明：本模块代码位于 com.zhanjh.hercules.gateway 包，
 * 与共享密钥相关的 JwtUtil 来自 hercules-common（com.zhanjh.hercules.auth 包）。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@SpringBootApplication
@EnableConfigurationProperties({GatewayProperties.class, AuthProperties.class})
public class HerculesGatewayApplication {

    /**
     * 网关启动入口（独立于 hercules-application 进程）。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(HerculesGatewayApplication.class, args);
    }

    /**
     * 注册 JWT 工具 Bean（secret/TTL 来自 hercules.auth.*，与后端共享同一密钥）。
     *
     * @param props 认证配置
     * @return JwtUtil 实例
     */
    @Bean
    public JwtUtil jwtUtil(AuthProperties props) {
        return new JwtUtil(props.getSecret(), props.getAccessTtl().toMillis());
    }
}
