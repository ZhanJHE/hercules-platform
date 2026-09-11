package com.zhanjh.hercules.agent.orchestrator;

import com.zhanjh.hercules.agent.config.AgentProperties;
import com.zhanjh.hercules.model.agent.ChatTurn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConversationHistory 单元测试（会话归属 + 会话数上限 + 历史上限）。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>会话归属：首次使用登记归属、同一用户可复用、他人被拒、入参为空被拒；</li>
 *   <li>只读归属判定：未知会话可用（返回空历史）、归属他人不可用、且不登记新会话；</li>
 *   <li>会话数上限：达到上限淘汰最旧会话，历史/待确认/归属三处一并清除；</li>
 *   <li>历史上限：超出轮数裁剪最旧发言。</li>
 * </ul>
 *
 * <p>纯单测：无 Spring 上下文、无外部中间件，构造真实 AgentProperties 驱动上限参数。
 *
 * @author zhanjh
 * @since 0.0.1
 */
class ConversationHistoryTest {

    /** 被测对象（每用例重建，保证用例间无状态残留）。 */
    private ConversationHistory history;

    /** 编排配置（用于设置会话数上限与历史上限）。 */
    private AgentProperties props;

    @BeforeEach
    void setUp() {
        props = new AgentProperties();
        history = new ConversationHistory(props);
    }

    /**
     * 验证点：首次认领登记归属；同一用户复用同一会话仍可用。
     */
    @Test
    void claimBindsFirstUserAndAllowsReuse() {
        assertThat(history.claim("s1", 100L)).isTrue();
        assertThat(history.claim("s1", 100L)).isTrue();
    }

    /**
     * 验证点：会话归属他人时认领被拒（越权收敛的核心分支）。
     */
    @Test
    void claimRejectsDifferentUser() {
        assertThat(history.claim("s1", 100L)).isTrue();

        assertThat(history.claim("s1", 200L)).isFalse();
        // 被拒不得篡改原归属：原用户仍可继续使用
        assertThat(history.claim("s1", 100L)).isTrue();
    }

    /**
     * 验证点：sessionId 或 userId 为空时一律拒绝（principal 缺失的防御分支）。
     */
    @Test
    void claimRejectsNullUserOrSession() {
        assertThat(history.claim(null, 100L)).isFalse();
        assertThat(history.claim("s1", null)).isFalse();
        assertThat(history.claim(null, null)).isFalse();
    }

    /**
     * 验证点（只读判定）：未知会话可用（空历史）、归属他人不可用；
     * 且判定过程不登记新会话——否则随机 sessionId 探测可撑大内存。
     */
    @Test
    void isOwnedByAllowsUnknownSessionButRejectsForeignOwner() {
        // 未知会话：放行（读到的必然是空历史）
        assertThat(history.isOwnedBy("unknown", 100L)).isTrue();
        // 未登记：同一未知会话对任意用户都放行，证明探测未留下归属
        assertThat(history.isOwnedBy("unknown", 200L)).isTrue();

        history.claim("s1", 100L);
        assertThat(history.isOwnedBy("s1", 100L)).isTrue();
        assertThat(history.isOwnedBy("s1", 200L)).isFalse();
        assertThat(history.isOwnedBy("s1", null)).isFalse();
    }

    /**
     * 验证点（会话数上限）：登记第 3 个会话（上限 2）时淘汰最旧会话，
     * 其历史、待确认与归属三处一并清除——归属释放后他人可重新认领该 sessionId。
     */
    @Test
    void maxSessionsEvictsOldest() {
        props.setMaxSessions(2);

        history.claim("s1", 100L);
        history.addUserTurn("s1", "最旧会话的发言");
        history.setPendingEnrollment("s1", 10L);

        history.claim("s2", 100L);
        history.claim("s3", 100L);

        // 最旧会话的历史与待确认均已清除
        assertThat(history.getHistory("s1")).isEmpty();
        assertThat(history.takePendingEnrollment("s1")).isNull();
        // 未被淘汰的 s3 仍绑定原用户：他人认领被拒（此判定不触发淘汰）
        assertThat(history.claim("s3", 200L)).isFalse();
        // 归属一并释放：s1 可被他人重新认领（若归属未清除，这里会是 false）。
        // 注意此调用本身会触发下一轮淘汰，故必须放在最后断言。
        assertThat(history.claim("s1", 200L)).isTrue();
    }

    /**
     * 验证点（历史上限）：轮数上限按「一轮 = 两条发言」折算，超出裁剪最旧。
     */
    @Test
    void historyLimitTrimsOldest() {
        props.setHistoryLimit(2); // 上限 2 轮 = 4 条

        for (int i = 1; i <= 5; i++) {
            history.addUserTurn("s1", "第" + i + "条");
        }

        List<ChatTurn> turns = history.getHistory("s1");
        assertThat(turns).hasSize(4);
        assertThat(turns.get(0).content()).isEqualTo("第2条");
        assertThat(turns.get(3).content()).isEqualTo("第5条");
        assertThat(turns.get(0).role()).isEqualTo(ChatTurn.USER);
    }
}
