package com.zhanjh.hercules.model.agent;

import java.util.List;

/**
 * 多轮对话中的一条发言（阶段 D）：role 取 "user" / "assistant"。
 *
 * @param role    发言方角色
 * @param content 发言内容
 * @author zhanjh
 * @since 0.0.1
 */
public record ChatTurn(String role, String content) {

    /** 用户发言的固定角色名。 */
    public static final String USER = "user";

    /** 助手发言的固定角色名。 */
    public static final String ASSISTANT = "assistant";
}
