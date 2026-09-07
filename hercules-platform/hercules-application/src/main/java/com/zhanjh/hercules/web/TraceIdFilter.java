package com.zhanjh.hercules.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.UUID;

/**
 * traceId 贯通过滤器（阶段 A+ 日志设计）：为每个请求建立全链路日志标识。
 *
 * <p>机制：优先复用网关注入的 {@code X-Trace-Id}（网关为入口时保证「网关→应用→同步链」
 * 日志可串联）；直连应用（无网关）时现场生成 16 位短 ID。写入 MDC 供日志 pattern
 * （{@code %X{traceId}}）输出，并回写响应头（前端/压测脚本可按 traceId 反查日志）。
 *
 * <p>AFTER_COMMIT 同步链消费者在同一线程执行，MDC 天然延续——同步链日志因此携带
 * 同一 traceId。阶段 F 接入 micrometer-tracing 后由 OTel 的 traceId 接管，本过滤器
 * 退化为无 tracing 场景的兜底。
 *
 * <p>注册方式：FilterRegistrationBean（HIGHEST_PRECEDENCE，先于 Security 过滤器链，
 * 确保安全层 401/403 日志也携带 traceId）。
 *
 * <p>线程安全性：MDC 基于线程绑定，finally 清理防止线程复用串号。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public class TraceIdFilter extends org.springframework.web.filter.OncePerRequestFilter {

    /** 请求/响应头名称。 */
    public static final String HEADER = "X-Trace-Id";
    /** MDC 键（logback pattern 中 %X{traceId}）。 */
    public static final String MDC_KEY = "traceId";

    /**
     * 过滤逻辑：取/生成 traceId → MDC + 响应头 → 放行 → finally 清理 MDC。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
