package com.zhanjh.hercules.agent.support;

import com.zhanjh.hercules.mapper.AgentTraceMapper;
import com.zhanjh.hercules.model.AgentTrace;
import com.zhanjh.hercules.model.agent.AgentContext;
import com.zhanjh.hercules.model.agent.AgentType;
import com.zhanjh.hercules.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 智能体调用记录写入器（阶段 D）：每次编排动作写一条 t_agent_trace。
 *
 * <p>失败策略：trace 落库失败只记 warn 不影响对话——审计是旁路，不能阻塞主链路。
 *
 * <p>线程安全性：无实例可变状态，可并发调用。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Component
public class AgentTraceWriter {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceWriter.class);

    /** trace 表 Mapper。 */
    private final AgentTraceMapper traceMapper;

    /** 编排配置（模型名用于 trace 记录）。 */
    private final AgentProperties props;

    public AgentTraceWriter(AgentTraceMapper traceMapper, AgentProperties props) {
        this.traceMapper = traceMapper;
        this.props = props;
    }

    /**
     * 写一条智能体调用记录（无 LLM 调用的纯规则智能体：耗时记 0）。
     *
     * @param ctx       对话上下文（traceId/sessionId 来源）
     * @param agentType 智能体类型
     * @param input     输入（用户消息或上游摘要）
     * @param output    输出（文本/摘要）
     * @param success   是否成功
     */
    public void write(AgentContext ctx, AgentType agentType, String input, String output, boolean success) {
        write(ctx, agentType, input, output, success, 0L);
    }

    /**
     * 写一条智能体调用记录（含 LLM 调用耗时）。
     *
     * @param ctx          对话上下文（traceId/sessionId 来源）
     * @param agentType    智能体类型
     * @param input        输入（用户消息或上游摘要）
     * @param output       输出（文本/摘要）
     * @param success      是否成功
     * @param llmLatencyMs LLM 调用耗时（毫秒）；纯规则智能体（SCHEDULING/EXECUTION）传 0
     */
    public void write(AgentContext ctx, AgentType agentType, String input, String output, boolean success,
                      long llmLatencyMs) {
        try {
            AgentTrace trace = new AgentTrace();
            trace.setTraceId(ctx.traceId());
            trace.setSessionId(ctx.sessionId());
            trace.setAgentName(agentType.name());
            trace.setInputPrompt(truncate(input, 512));
            trace.setOutputContent(truncate(output, 2048));
            trace.setLlmModel(props.getModel());
            // AgentTrace.llmLatencyMs 为 Integer：毫秒级耗时远小于上限，仍做钳制以防溢出
            trace.setLlmLatencyMs(llmLatencyMs > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) llmLatencyMs);
            trace.setStatus(success ? 0 : 1);
            trace.setCreateTime(LocalDateTime.now());
            traceMapper.insert(trace);
        } catch (Exception e) {
            log.warn("[hercules-agent] agent trace insert failed (session={}): {}", ctx.sessionId(), e.getMessage());
        }
    }

    /**
     * 截断超长文本（trace 列容量保护）。
     *
     * @param text      原文本，可 null
     * @param maxLength 最大长度
     * @return 截断后文本
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }
}
