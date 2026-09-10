package com.zhanjh.hercules.model.agent;

import java.util.Map;

/**
 * 智能体执行结果（阶段 D）：content 为面向用户的文本（流式输出聚合后的全文），
 * data 为结构化附加数据（如候选课程列表、冲突明细），供前端/接口层渲染。
 *
 * <p>Record 不可变，安全共享（线程安全）。
 *
 * @param agent   产出该结果的智能体类型
 * @param success 执行是否成功（失败时 content 为降级/错误提示）
 * @param content 面向用户的文本内容
 * @param data    结构化附加数据，可为 null
 * @param error   失败原因（success=false 时有值）
 * @author zhanjh
 * @since 0.0.1
 */
public record AgentResult(AgentType agent, boolean success, String content, Map<String, Object> data, String error) {

    /**
     * 构建成功结果。
     *
     * @param agent   智能体类型
     * @param content 文本内容
     * @param data    结构化附加数据，可 null
     * @return 成功结果
     */
    public static AgentResult ok(AgentType agent, String content, Map<String, Object> data) {
        return new AgentResult(agent, true, content, data, null);
    }

    /**
     * 构建失败结果。
     *
     * @param agent 智能体类型
     * @param error 失败原因
     * @return 失败结果（content 为面向用户的兜底提示）
     */
    public static AgentResult fail(AgentType agent, String error) {
        return new AgentResult(agent, false, null, null, error);
    }
}
