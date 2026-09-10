package com.zhanjh.hercules.agent.orchestrator;

/**
 * 对话意图（阶段 D）：路由器的输出，决定编排器走哪条智能体链。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public enum Intent {

    /** 查询/推荐类：给出候选课程与推荐理由（不走写路径）。 */
    RECOMMEND,

    /** 选课请求类：先做冲突校验，通过后进入待确认状态。 */
    ENROLL,

    /** 确认执行类：对上一次通过校验的选课请求执行写入。 */
    CONFIRM,

    /** 未识别：降级为推荐链（检索 + 提示）。 */
    UNKNOWN
}
