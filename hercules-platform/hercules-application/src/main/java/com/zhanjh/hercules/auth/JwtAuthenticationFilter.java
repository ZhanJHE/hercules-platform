package com.zhanjh.hercules.auth;

import com.zhanjh.hercules.common.JsonUtil;
import com.zhanjh.hercules.common.R;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器（阶段 A+）：解析 Bearer Token → 验签/过期 → 黑名单 → 写入 SecurityContext。
 *
 * <p>处理流程：
 * <ol>
 *   <li>无 Authorization 头 → 直接放行（未认证状态；受保护端点由授权规则回 401）；</li>
 *   <li>有 Bearer 但解析失败（伪造/过期/格式错误）→ 直接回 401 JSON（R.fail），不放行；</li>
 *   <li>黑名单命中（已登出）→ 回 401 JSON；黑名单查询故障 fail-open 放行（见 AuthStorePort）；</li>
 *   <li>合法 → 构建 Authentication（principal=AuthClaims，authority=ROLE_{role}）后放行。</li>
 * </ol>
 *
 * <p>401/403 响应体统一为 R.fail JSON（与业务错误同构），前端拦截器按 HTTP 状态码分流
 * （401 → 静默刷新/跳登录，403 → 无权限提示）。
 *
 * <p>线程安全性：无可变状态；SecurityContext 每请求设置、finally 清理。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final AuthStorePort authStore;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, AuthStorePort authStore) {
        this.jwtUtil = jwtUtil;
        this.authStore = authStore;
    }

    /**
     * 过滤逻辑：见类注释流程。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            // 无凭据：放行为匿名（受保护端点由授权规则返回 401）
            chain.doFilter(request, response);
            return;
        }
        String token = header.substring(7);
        JwtUtil.AuthClaims claims;
        try {
            claims = jwtUtil.parse(token);
        } catch (Exception e) {
            // 伪造/过期/格式错误：直接 401，不放行（即使命中白名单路径，携带无效凭据也应拒绝）
            writeR(response, 401, "token 无效或已过期");
            return;
        }
        if (authStore.isBlacklisted(claims.jti())) {
            writeR(response, 401, "token 已失效（已登出）");
            return;
        }

        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + claims.role()));
        var authentication = new UsernamePasswordAuthenticationToken(claims, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 输出统一 401 JSON 响应体（R.fail）。
     *
     * @param response HTTP 响应
     * @param message  错误信息
     */
    private void writeR(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JsonUtil.toJson(R.fail(status, message)));
    }
}
