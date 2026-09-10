package com.zhanjh.hercules.model.agent;

import java.util.List;

/**
 * 智能体执行上下文（阶段 D）：一次对话请求的全部输入。
 *
 * <p>由 ChatController 构建：sessionId 为前端生成的会话标识（多轮记忆的键），
 * traceId 取自网关下发/应用生成（MDC），userId/role 取自 JWT 认证主体，
 * studentId 供执行智能体调选课端口（执行侧仍会二次校验 STUDENT 角色）。
 * history 为该会话此前的发言序列（时间正序，不含本条 message）。
 *
 * <p>Record 不可变，智能体链中安全共享（线程安全）。
 *
 * @param sessionId 会话标识，前端生成（UUID），不应为 null
 * @param traceId   全链路追踪 ID，不应为 null
 * @param userId    登录用户主键
 * @param studentId 学生业务号（ADMIN 为 null）
 * @param role      角色（STUDENT / ADMIN）
 * @param message   本条用户消息，不应为 null
 * @param history   此前发言序列（可空列表，不含本条 message）
 * @author zhanjh
 * @since 0.0.1
 */
public record AgentContext(String sessionId,
                           String traceId,
                           Long userId,
                           Long studentId,
                           String role,
                           String message,
                           List<ChatTurn> history) {

    /**
     * 判断当前用户是否学生角色（执行智能体的前置校验依据之一）。
     *
     * @return true-角色为 STUDENT 且 studentId 非空
     */
    public boolean isStudent() {
        return "STUDENT".equals(role) && studentId != null;
    }
}
