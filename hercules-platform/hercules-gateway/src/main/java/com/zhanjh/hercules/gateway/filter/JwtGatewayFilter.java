package com.zhanjh.hercules.gateway.filter;

import com.zhanjh.hercules.auth.JwtUtil;
import com.zhanjh.hercules.common.JsonUtil;
import com.zhanjh.hercules.common.R;
import com.zhanjh.hercules.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 网关全局 JWT 过滤器（阶段 H 最小实现）：白名单放行 + 验签/过期 + 可信透传头注入。
 *
 * <p>处理流程：
 * <ol>
 *   <li>白名单（hercules.gateway.whitelist）命中 → 直接放行；</li>
 *   <li>剥离外部伪造的 X-Auth-UserId / X-Auth-Role 请求头（防越权经典漏洞：先 remove 后 inject）；</li>
 *   <li>无 Bearer → 401；解析失败（伪造/过期/格式错误）→ 401；</li>
 *   <li>校验通过 → 注入 X-Auth-UserId / X-Auth-Role 透传头后转发（下游服务可信任）。</li>
 * </ol>
 *
 * <p>黑名单查询暂缓（应用层 JWT 过滤器已有黑名单兜底，双层校验）；限流留待后续。
 *
 * <p>线程安全性：无可变状态（AntPathMatcher 线程安全），可并发调用。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Component
public class JwtGatewayFilter implements GlobalFilter, Ordered {

    /** 透传头：用户主键（经网关剥离伪造后注入，下游可信任）。 */
    public static final String HEADER_USER_ID = "X-Auth-UserId";
    /** 透传头：角色。 */
    public static final String HEADER_ROLE = "X-Auth-Role";

    private final JwtUtil jwtUtil;
    private final GatewayProperties gatewayProps;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * 构造注入。
     *
     * @param jwtUtil      JWT 工具（与后端共享密钥）
     * @param gatewayProps 网关配置（白名单）
     */
    public JwtGatewayFilter(JwtUtil jwtUtil, GatewayProperties gatewayProps) {
        this.jwtUtil = jwtUtil;
        this.gatewayProps = gatewayProps;
    }

    /**
     * 过滤器顺序：最先执行，先于路由转发。
     *
     * @return -100
     */
    @Override
    public int getOrder() {
        return -100;
    }

    /**
     * 过滤逻辑：白名单 → 剥离伪造头 → 验签 → 注入透传头 → 放行；失败 → 401 JSON。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        List<String> whitelist = gatewayProps.getWhitelist();
        for (String pattern : whitelist) {
            if (pathMatcher.match(pattern, path)) {
                return chain.filter(exchange);
            }
        }

        // 先剥离外部伪造的透传头（无论后续是否通过校验）
        ServerHttpRequest stripped = exchange.getRequest().mutate()
                .headers(h -> {
                    h.remove(HEADER_USER_ID);
                    h.remove(HEADER_ROLE);
                })
                .build();

        String auth = stripped.getHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return reject(exchange, "未登录或登录已过期");
        }
        JwtUtil.AuthClaims claims;
        try {
            claims = jwtUtil.parse(auth.substring(7));
        } catch (Exception e) {
            return reject(exchange, "token 无效或已过期");
        }

        // 校验通过：注入可信透传头（下游服务可信任）
        ServerHttpRequest trusted = stripped.mutate()
                .headers(h -> {
                    h.add(HEADER_USER_ID, String.valueOf(claims.userId()));
                    h.add(HEADER_ROLE, claims.role());
                })
                .build();
        return chain.filter(exchange.mutate().request(trusted).build());
    }

    /**
     * 输出统一 401 JSON 响应体（R.fail，与业务错误同构）。
     *
     * @param exchange 当前交换
     * @param message  错误信息
     * @return 完成信号
     */
    private Mono<Void> reject(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = JsonUtil.toJson(R.fail(401, message)).getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
