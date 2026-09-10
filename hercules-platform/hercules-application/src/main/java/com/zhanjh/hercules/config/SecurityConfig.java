package com.zhanjh.hercules.config;

import com.zhanjh.hercules.auth.AuthProperties;
import com.zhanjh.hercules.auth.AuthStorePort;
import com.zhanjh.hercules.auth.JwtAuthenticationFilter;
import com.zhanjh.hercules.auth.JwtUtil;
import com.zhanjh.hercules.auth.RedisAuthStore;
import com.zhanjh.hercules.common.JsonUtil;
import com.zhanjh.hercules.common.R;
import com.zhanjh.hercules.web.TraceIdFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置（阶段 A+ 认证）：无状态 JWT 鉴权，阶段 H 验签逻辑迁移网关。
 *
 * <p>授权规则：
 * <ul>
 *   <li>白名单（permitAll）：/api/v1/auth/login、/api/v1/auth/refresh、
 *       /actuator/health、/actuator/prometheus（探针与指标拉取）；</li>
 *   <li>STUDENT：/api/v1/enrollment/**（选课/退课/我的选课）；</li>
 *   <li>ADMIN：/api/v1/cache/stats、/api/v1/debug/**（治理驾驶舱与演示工具）、其余 /actuator/**；</li>
 *   <li>认证即可：/api/v1/courses/**（学生与管理员都可查）；</li>
 *   <li>其余请求 denyAll（白名单外默认拒绝）。</li>
 * </ul>
 *
 * <p>401/403 以 R.fail JSON 返回（与业务错误同构）；会话无状态；CSRF 关闭（纯 Token API）。
 *
 * <p>两阶段迁移（《认证设计.md》§五）：阶段 H 网关接管验签/黑名单后，本配置中的
 * JWT 过滤器替换为「透传头 → SecurityContext」轻量实现并校验网关共享密钥头。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    /** 无需认证的白名单（与 JwtAuthenticationFilter 的语义配合：无凭据放行、有凭据必验）。 */
    private static final String[] WHITELIST = {
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/actuator/health",
            "/actuator/prometheus"
    };

    /**
     * 构建 SecurityFilterChain：无状态 + 白名单 + 角色 URL 规则 + JWT 过滤器。
     *
     * @param http      HttpSecurity 构建器
     * @param jwtUtil   JWT 工具（解析用）
     * @param authStore 认证存储（黑名单查询）
     * @return 过滤器链
     * @throws Exception 构建异常
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtUtil jwtUtil, AuthStorePort authStore)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(WHITELIST).permitAll()
                        .requestMatchers("/api/v1/auth/logout").authenticated()
                        .requestMatchers("/api/v1/chat/**").authenticated()  // 阶段 D：自然语言对话（登录即可，执行侧再校验 STUDENT）
                        .requestMatchers("/api/v1/enrollment/**").hasRole("STUDENT")
                        .requestMatchers("/api/v1/cache/stats", "/api/v1/debug/**").hasRole("ADMIN")
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/courses/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                writeR(response, 401, "未登录或登录已过期"))
                        .accessDeniedHandler((request, response, ex) ->
                                writeR(response, 403, "无权限访问该资源")))
                .addFilterBefore(new JwtAuthenticationFilter(jwtUtil, authStore),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 注册 JWT 工具 Bean（secret/TTL 来自 hercules.auth.*，secret 不足 256 位将拒绝启动）。
     *
     * @param props 认证配置
     * @return JwtUtil 实例
     */
    @Bean
    public JwtUtil jwtUtil(AuthProperties props) {
        return new JwtUtil(props.getSecret(), props.getAccessTtl().toMillis());
    }

    /**
     * 注册 BCrypt 密码编码器（强度 10，密文 VARCHAR(60)）。
     *
     * @return BCrypt 编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 注册认证存储 Bean（Redis 实现：refresh 存取 + jti 黑名单）。
     * 测试环境由 TestStubsConfig 的 @Primary 内存桩覆盖。
     *
     * @param redisTemplate Redis 字符串模板
     * @return 认证存储实现
     */
    @Bean
    public AuthStorePort authStorePort(StringRedisTemplate redisTemplate) {
        return new RedisAuthStore(redisTemplate);
    }

    /**
     * 注册 traceId 贯通过滤器（阶段 A+ 日志设计，HIGHEST_PRECEDENCE 保证先于
     * Security 过滤器链，安全层 401/403 日志也携带 traceId）。
     *
     * @return 过滤器注册器（匹配所有路径）
     */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /**
     * 输出统一 JSON 错误响应（401/403 与业务错误同构，前端按 HTTP 状态码分流）。
     *
     * @param response HTTP 响应
     * @param status   HTTP 状态码
     * @param message  错误信息
     */
    private static void writeR(jakarta.servlet.http.HttpServletResponse response, int status, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JsonUtil.toJson(R.fail(status, message)));
    }
}
