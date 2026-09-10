package com.zhanjh.hercules.model.agent;

/**
 * 智能体类型（阶段 D）：编排器按此类型注册与分发，新增智能体新增枚举值 + 注册即可，不改调度器。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public enum AgentType {

    /** 意图路由：判断用户消息属于哪类意图。 */
    ROUTING,

    /** 课程推荐：候选检索 + LLM 生成推荐理由。 */
    RECOMMENDATION,

    /** 排课冲突校验：时间片重叠 / 先修课（纯规则计算）。 */
    SCHEDULING,

    /** 执行选课：用户确认后调用选课端口。 */
    EXECUTION
}
