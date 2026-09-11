package com.zhanjh.hercules.agent.orchestrator;

import com.zhanjh.hercules.agent.config.AgentProperties;
import com.zhanjh.hercules.model.agent.ChatTurn;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 会话历史与待确认状态（阶段 D，内存态）：
 * <ul>
 *   <li>多轮对话历史：按 sessionId 存 user/assistant 发言，上限 hercules.agent.history-limit
 *       轮（超出淘汰最旧）；</li>
 *   <li>待确认选课：ENROLL 意图通过冲突校验后暂存 courseId，CONFIRM 意图取出执行——
 *       「确认制」的状态载体，同一会话同一时刻至多一条待确认请求；</li>
 *   <li>会话归属：sessionId 首次被使用时绑定 userId，此后仅同一用户可读写该会话
 *       （越权收敛；跨用户访问由调用方 ChatController 转 403）。</li>
 * </ul>
 *
 * <p>边界（记录备查）：内存态仅存于单实例，应用重启即清空（待确认请求需重新发起），
 * 多实例部署需迁移到 Redis；会话数达到 hercules.agent.max-sessions（默认 500）时，
 * 按登记顺序淘汰最旧会话——历史、待确认与归属三处一并清除，被淘汰会话等价于新会话。
 *
 * <p>线程安全性：sessions/pendingEnrollments/sessionOwners 均为 ConcurrentHashMap，
 * 单会话的 list 操作在 synchronized(deque) 内完成，可并发访问不同会话；
 * 会话淘汰在 claim 登记新会话时触发。
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

    /** 会话归属：sessionId → 首次使用该会话的用户主键（越权判定依据）。 */
    private final Map<String, Long> sessionOwners = new ConcurrentHashMap<>();

    /** 会话登记顺序（队首最旧）：仅供超上限时淘汰使用，不做并发强一致保证。 */
    private final Deque<String> sessionOrder = new ConcurrentLinkedDeque<>();

    /** 编排配置（历史上限与会话数上限）。 */
    private final AgentProperties props;

    public ConversationHistory(AgentProperties props) {
        this.props = props;
    }

    /**
     * 认领会话（POST 入口调用）：首次使用时登记归属，已登记则校验是否为同一用户。
     *
     * <p>与 {@link #isOwnedBy} 的区别：本方法会为新会话登记归属，故只应由写入路径调用；
     * 并发首次认领时以 putIfAbsent 的先到者为归属，后到者按返回值判定。
     *
     * @param sessionId 会话标识
     * @param userId    当前用户主键（取自 JWT 主体），不允许为 null
     * @return true-会话可用（首次登记或归属一致）；false-归属他人或入参为空
     */
    public boolean claim(String sessionId, Long userId) {
        if (sessionId == null || userId == null) {
            return false;
        }
        Long existing = sessionOwners.get(sessionId);
        if (existing != null) {
            return existing.equals(userId);
        }
        evictIfNeeded();
        Long prev = sessionOwners.putIfAbsent(sessionId, userId);
        if (prev != null) {
            return prev.equals(userId);
        }
        sessionOrder.addLast(sessionId);
        return true;
    }

    /**
     * 只读归属判定（GET 入口调用）：不登记新会话，避免探测随机 sessionId 造成内存增长。
     *
     * @param sessionId 会话标识
     * @param userId    当前用户主键（取自 JWT 主体）
     * @return true-未知会话（历史为空）或归属一致；false-归属他人或入参为空
     */
    public boolean isOwnedBy(String sessionId, Long userId) {
        if (sessionId == null || userId == null) {
            return false;
        }
        Long owner = sessionOwners.get(sessionId);
        return owner == null || owner.equals(userId);
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
     * 会话数达到上限时按登记顺序淘汰最旧会话。
     *
     * <p>淘汰范围覆盖历史、待确认与归属三处，保证不留孤儿状态；登记队列中可能残留
     * 已被其它路径移除的 sessionId，移除操作对不存在的键为无害操作。
     * 循环条件以 size 为准、队列为空时退出，避免队列与 map 不一致导致死循环。
     */
    private void evictIfNeeded() {
        int max = props.getMaxSessions();
        while (sessionOwners.size() >= max) {
            String oldest = sessionOrder.pollFirst();
            if (oldest == null) {
                break;
            }
            sessionOwners.remove(oldest);
            sessions.remove(oldest);
            pendingEnrollments.remove(oldest);
        }
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
