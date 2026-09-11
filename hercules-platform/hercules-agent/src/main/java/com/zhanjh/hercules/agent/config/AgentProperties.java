package com.zhanjh.hercules.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多智能体编排配置项（前缀 hercules.agent，阶段 D）。
 *
 * <p>LLM 模型名/温度等模型参数由 spring.ai.openai.chat.options 配置（Spring AI 官方键），
 * 本类只承载编排行为开关与限额。setter 即校验，配置错误拒绝启动。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@ConfigurationProperties(prefix = "hercules.agent")
public class AgentProperties {

    /** 对话总开关：false 时 chat 端点直接返回固定降级提示，不触达 LLM（hercules.agent.enabled）。 */
    private boolean enabled = true;

    /** LLM 单次调用超时秒数（阻塞与流式共用，hercules.agent.timeout-seconds）。 */
    private int timeoutSeconds = 30;

    /** 每会话内存历史上限（轮数，超出淘汰最旧，hercules.agent.history-limit）。 */
    private int historyLimit = 20;

    /** 内存会话数上限（达到上限时淘汰最旧会话，hercules.agent.max-sessions）。 */
    private int maxSessions = 500;

    /** 模型名（hercules.agent.model）：与 spring.ai.openai.chat.options.model 保持一致，用于 trace 记录与文档口径。 */
    private String model = "glm-4.5-air";

    /**
     * 读取对话总开关。
     *
     * @return true-启用（默认）
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 覆盖对话总开关。
     *
     * @param enabled 开关
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 读取 LLM 超时秒数。
     *
     * @return 秒数
     */
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * 覆盖 LLM 超时秒数。
     *
     * @param timeoutSeconds 秒数，必须为正
     * @throws IllegalArgumentException 非正数时抛出
     */
    public void setTimeoutSeconds(int timeoutSeconds) {
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("hercules.agent.timeout-seconds 必须 >= 1，实际值: " + timeoutSeconds);
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 读取会话历史上限。
     *
     * @return 轮数
     */
    public int getHistoryLimit() {
        return historyLimit;
    }

    /**
     * 覆盖会话历史上限。
     *
     * @param historyLimit 轮数，必须 >= 0
     * @throws IllegalArgumentException 负数时抛出
     */
    public void setHistoryLimit(int historyLimit) {
        if (historyLimit < 0) {
            throw new IllegalArgumentException("hercules.agent.history-limit 必须 >= 0，实际值: " + historyLimit);
        }
        this.historyLimit = historyLimit;
    }

    /**
     * 读取内存会话数上限。
     *
     * @return 会话数上限
     */
    public int getMaxSessions() {
        return maxSessions;
    }

    /**
     * 覆盖内存会话数上限。
     *
     * @param maxSessions 会话数上限，必须 >= 1（否则新会话登记后立即被淘汰）
     * @throws IllegalArgumentException 小于 1 时抛出
     */
    public void setMaxSessions(int maxSessions) {
        if (maxSessions < 1) {
            throw new IllegalArgumentException("hercules.agent.max-sessions 必须 >= 1，实际值: " + maxSessions);
        }
        this.maxSessions = maxSessions;
    }

    /**
     * 读取模型名（trace 记录用）。
     *
     * @return 模型名
     */
    public String getModel() {
        return model;
    }

    /**
     * 覆盖模型名。
     *
     * @param model 模型名，不允许为空白
     * @throws IllegalArgumentException 空白时抛出
     */
    public void setModel(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("hercules.agent.model 不允许为空白");
        }
        this.model = model;
    }
}
