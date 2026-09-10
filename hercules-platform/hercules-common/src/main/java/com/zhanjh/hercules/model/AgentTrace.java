package com.zhanjh.hercules.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 智能体调用记录实体，对应起步文档 §5.3 的 t_agent_trace 表（MVP 已建占位表，阶段 D 启用）。
 *
 * <p>每个智能体每执行一次写一行：BaseAgent 模板统一落库（成功/失败均记），
 * traceId/sessionId 与对话请求对齐，供全链路排查与答辩演示回放。
 *
 * <p>线程安全性：普通可变 POJO，仅生产侧单点写入。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@TableName("t_agent_trace")
public class AgentTrace {

    /** 主键，数据库自增（t_agent_trace.id，IdType.AUTO）。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 全链路追踪 ID（t_agent_trace.trace_id），与 HTTP 请求 traceId 一致。 */
    private String traceId;

    /** 对话会话 ID（t_agent_trace.session_id），前端生成。 */
    private String sessionId;

    /** 智能体名称（t_agent_trace.agent_name），如 "RecommendationAgent"。 */
    private String agentName;

    /** 输入内容（t_agent_trace.input_prompt）：用户消息或上游智能体产出摘要。 */
    private String inputPrompt;

    /** 输出内容（t_agent_trace.output_content）：智能体产出文本（流式聚合后全文）。 */
    private String outputContent;

    /** 使用的 LLM 模型（t_agent_trace.llm_model），纯规则智能体记 "rule-engine"。 */
    private String llmModel;

    /** LLM 调用耗时毫秒（t_agent_trace.llm_latency_ms）。 */
    private Integer llmLatencyMs;

    /** 状态（t_agent_trace.status）：0-成功 1-失败。 */
    private Integer status;

    /** 记录时间（t_agent_trace.create_time）。 */
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getInputPrompt() {
        return inputPrompt;
    }

    public void setInputPrompt(String inputPrompt) {
        this.inputPrompt = inputPrompt;
    }

    public String getOutputContent() {
        return outputContent;
    }

    public void setOutputContent(String outputContent) {
        this.outputContent = outputContent;
    }

    public String getLlmModel() {
        return llmModel;
    }

    public void setLlmModel(String llmModel) {
        this.llmModel = llmModel;
    }

    public Integer getLlmLatencyMs() {
        return llmLatencyMs;
    }

    public void setLlmLatencyMs(Integer llmLatencyMs) {
        this.llmLatencyMs = llmLatencyMs;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
