package com.zhanjh.hercules.agent.orchestrator;

import com.zhanjh.hercules.agent.config.AgentProperties;
import com.zhanjh.hercules.model.agent.ChatTurn;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话历史与待确认状态（阶段 D，内存态）：
 * <ul>
 *   <li>多轮对话历史：按 sessionId 存 user/assistant 发言，上限 hercules.agent.history-limit
 *       轮（超出淘汰最旧）；</li>
 *   <li>待确认选课：ENROLL 意图通过冲突校验后暂存 courseId，CONFIRM 意图取出执行——
 *       「确认制」的状态载体，同一会话同一时刻至多一条待确认请求。</li>
 * </ul>
 *
 * <p>边界（记录备查）：内存态仅存于单实例，应用重启即清空（待确认请求需重新发起），
 * 多实例部署需迁移到 Redis（待阶段 G 前端接入后按需评估）。
 *
 * <p>线程安全性：sessions/pending 均为 ConcurrentHashMap，单会话的 list 操作在
 * synchronized(session) 内完成，可并发访问不同会话。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Component
public class ConversationHistory {

    /** 会话历史：sessionId → 发言队列。 */
    private final Map<String, Deque<ChatTurn>> sessions = new ConcurrentHashMap<>();

    /** 待确认选课：sessionId → courseId。 */
    private final Map<String, Long> pendingEnrollments = new ConcurrentHashMap<>();

    /** 编排配置（历史上限）。 */
    private final AgentProperties props;

    public ConversationHistory(AgentProperties props) {
        this.props = props;
    }

    /**
     * 读取会话历史（不含本轮消息）。
     *
     * @param sessionId 会话标识
     * @return 历史发言（时间正序，从不为 null）
     */
    public List<ChatTurn> getHistory(String sessionId) {
        Deque<ChatTurn> turns = sessions.get(sessionId);
        return turns == null ? List.of() : List.copyOf(turns);
    }

    /**
     * 追加用户发言（超限淘汰最旧）。
     *
     * @param sessionId 会话标识
     * @param content   发言内容
     */
    public void addUserTurn(String sessionId, String content) {
        append(sessionId, new ChatTurn(ChatTurn.USER, content));
    }

    /**
     * 追加助手发言（流式聚合后的全文，超限淘汰最旧）。
     *
     * @param sessionId 会话标识
     * @param content   发言内容
     */
    public void addAssistantTurn(String sessionId, String content) {
        append(sessionId, new ChatTurn(ChatTurn.ASSISTANT, content));
    }

    /**
     * 记录待确认选课请求（覆盖旧值：同一会话以最后一次通过校验的请求为准）。
     *
     * @param sessionId 会话标识
     * @param courseId  待选课程主键
     */
    public void setPendingEnrollment(String sessionId, Long courseId) {
        pendingEnrollments.put(sessionId, courseId);
    }

    /**
     * 取出并清除待确认选课请求。
     *
     * @param sessionId 会话标识
     * @return 待选课程主键；无待确认请求时为 null
     */
    public Long takePendingEnrollment(String sessionId) {
        return pendingEnrollments.remove(sessionId);
    }

    /**
     * 追加发言并按上限裁剪。
     *
     * @param sessionId 会话标识
     * @param turn      发言
     */
    private void append(String sessionId, ChatTurn turn) {
        sessions.compute(sessionId, (k, deque) -> {
            if (deque == null) {
                deque = new ArrayDeque<>();
            }
            synchronized (deque) {
                deque.addLast(turn);
                int maxTurns = props.getHistoryLimit() * 2; // 一轮 = user + assistant 两条
                while (deque.size() > maxTurns) {
                    deque.pollFirst();
                }
            }
            return deque;
        });
    }
}
