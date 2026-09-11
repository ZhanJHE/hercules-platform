package com.zhanjh.hercules.controller;

import com.zhanjh.hercules.agent.config.AgentProperties;
import com.zhanjh.hercules.agent.orchestrator.AgentOrchestrator;
import com.zhanjh.hercules.agent.orchestrator.ConversationHistory;
import com.zhanjh.hercules.auth.JwtUtil;
import com.zhanjh.hercules.common.BusinessException;
import com.zhanjh.hercules.common.JsonUtil;
import com.zhanjh.hercules.common.R;
import com.zhanjh.hercules.model.agent.AgentContext;
import com.zhanjh.hercules.model.agent.ChatTurn;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 自然语言对话接口（阶段 D）：POST /api/v1/chat 为 SSE 流式端点，
 * token 事件逐片下发、meta 事件承载结构化数据（候选/冲突明细）、error 事件承载降级提示；
 * GET /api/v1/chat/history/{sessionId} 返回内存会话历史。
 *
 * <p>事件序列：token* → meta → complete。LLM 侧任何错误已在编排器内降级为文本流
 * （不会以 error 信号到达控制器），error 事件仅作最后兜底。
 *
 * <p>认证与越权：端点要求登录；studentId 一律取自 JWT 主体（与选课接口同源），
 * 前端传入的任何身份信息不参与执行语义。会话维度：sessionId 首次被使用时绑定当前用户，
 * 此后仅属主可读写（他人访问返回 403）——避免猜到 sessionId 即可读取他人对话记录，
 * 或劫持他人（同一 sessionId 下）的待确认选课请求。
 *
 * <p>线程安全性：无实例可变状态；SSE 回调运行在 reactor 线程，emitter 线程安全。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** 编排器（对话总入口）。 */
    private final AgentOrchestrator orchestrator;

    /** 会话历史与待确认状态。 */
    private final ConversationHistory history;

    /** 编排配置（enabled 开关）。 */
    private final AgentProperties agentProps;

    public ChatController(AgentOrchestrator orchestrator,
                          ConversationHistory history,
                          AgentProperties agentProps) {
        this.orchestrator = orchestrator;
        this.history = history;
        this.agentProps = agentProps;
    }

    /**
     * 对话请求体。
     *
     * @param sessionId 会话标识（前端生成 UUID，多轮记忆的键）
     * @param message   用户消息
     * @author zhanjh
     * @since 0.0.1
     */
    public record ChatRequest(@NotBlank String sessionId, @NotBlank String message) {
    }

    /**
     * 对话（SSE 流式）。
     *
     * <p>请求示例：{@code POST /api/v1/chat}，body {@code {"sessionId":"uuid","message":"推荐几门 3 学分的课"}}；
     * 响应为 text/event-stream：token 事件（正文分片）→ meta 事件（结构化数据 JSON）→ 完成。
     *
     * @param request   对话请求体（@Valid 校验非空）
     * @param principal JWT 认证主体（JwtAuthenticationFilter 写入 SecurityContext）
     * @return SSE 发射器
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody @Valid ChatRequest request,
                           @AuthenticationPrincipal JwtUtil.AuthClaims principal) {
        // 会话归属校验先于一切分支：agent 关闭时的降级路径同样不可跨用户访问
        if (!history.claim(request.sessionId(), principal == null ? null : principal.userId())) {
            throw new BusinessException(403, "无权访问该会话");
        }
        SseEmitter emitter = new SseEmitter(120_000L);
        if (!agentProps.isEnabled()) {
            return completeWithText(emitter, "对话功能暂未开启。可直接使用课程查询与选课接口。", null);
        }
        String traceId = MDC.get("traceId");
        AgentContext ctx = new AgentContext(
                request.sessionId(),
                traceId == null ? "" : traceId,
                principal == null ? null : principal.userId(),
                principal == null ? null : principal.studentId(),
                principal == null ? null : principal.role(),
                request.message(),
                history.getHistory(request.sessionId()));
        try {
            AgentOrchestrator.Orchestration orchestration = orchestrator.handle(ctx);
            StringBuilder collected = new StringBuilder();
            orchestration.tokens().subscribe(
                    token -> {
                        try {
                            collected.append(token);
                            emitter.send(SseEmitter.event().name("token")
                                    .data(token, new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8)));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    },
                    err -> {
                        // 理论不可达（编排器内已降级），兜底为 error 事件
                        log.warn("[hercules-agent] chat stream error (session={}): {}", request.sessionId(), err.getMessage());
                        completeWithError(emitter, "对话服务暂时不可用，请稍后再试。");
                    },
                    () -> {
                        history.addAssistantTurn(request.sessionId(), collected.toString());
                        completeWithMeta(emitter, orchestration.metadata().get());
                    });
        } catch (Exception e) {
            // 编排同步阶段异常（检索/校验失败等）：降级为错误提示事件
            log.warn("[hercules-agent] chat orchestration failed (session={}): {}", request.sessionId(), e.getMessage());
            return completeWithText(emitter, "对话处理失败，请稍后再试。", null);
        }
        return emitter;
    }

    /**
     * 会话历史（内存态，应用重启清空——已知边界）。
     *
     * <p>越权收敛：仅会话属主可读，归属他人返回 403；isOwnedBy 不登记新会话，
     * 故以随机 sessionId 探测无副作用（未知会话返回空列表）。
     *
     * @param sessionId 会话标识
     * @param principal JWT 认证主体（JwtAuthenticationFilter 写入 SecurityContext）
     * @return 发言列表（时间正序）
     */
    @GetMapping("/history/{sessionId}")
    public R<List<ChatTurn>> history(@PathVariable String sessionId,
                                     @AuthenticationPrincipal JwtUtil.AuthClaims principal) {
        if (!history.isOwnedBy(sessionId, principal == null ? null : principal.userId())) {
            throw new BusinessException(403, "无权访问该会话");
        }
        return R.ok(history.getHistory(sessionId));
    }

    /**
     * 单段文本形态的 SSE 完成（开关关闭/同步异常路径）。
     *
     * @param emitter 发射器
     * @param text    文本
     * @param meta    元数据，可 null
     * @return 同一发射器
     */
    private SseEmitter completeWithText(SseEmitter emitter, String text, String meta) {
        try {
            emitter.send(SseEmitter.event().name("token").data(text,
                    new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8)));
            if (meta != null) {
                emitter.send(SseEmitter.event().name("meta").data(meta, MediaType.APPLICATION_JSON));
            }
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * meta 事件 + 正常完成。
     *
     * @param emitter 发射器
     * @param meta    结构化元数据（序列化为 JSON）
     */
    private void completeWithMeta(SseEmitter emitter, java.util.Map<String, Object> meta) {
        try {
            emitter.send(SseEmitter.event().name("meta").data(toJson(meta), MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    /**
     * 错误事件 + 完成。
     *
     * @param emitter 发射器
     * @param message 错误提示
     */
    private void completeWithError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(message,
                    new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8)));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    /**
     * 元数据 JSON 序列化（SSE meta 事件载荷；JsonUtil 失败以 {} 兜底，不影响完成）。
     *
     * @param meta 元数据
     * @return JSON 字符串
     */
    private String toJson(java.util.Map<String, Object> meta) {
        try {
            return JsonUtil.toJson(meta);
        } catch (Exception e) {
            return "{}";
        }
    }
}
