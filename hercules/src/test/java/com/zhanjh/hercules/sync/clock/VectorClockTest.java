package com.zhanjh.hercules.sync.clock;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VectorClock 纯单元测试：验证向量时钟的自增、逐分量 max 合并、支配/并发判定与 JSON 序列化往返。
 *
 * <p>被测对象：VectorClock（increment/merge/snapshot/toJson/fromJson）与 ClockRelation 四种判定
 * （AFTER 支配、BEFORE 被支配、EQUAL 相等、CONCURRENT 并发）。不依赖 Spring 与外部资源，
 * 全部以固定 Map 或链式自增构造时钟后断言。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>{@code incrementAccumulatesPerNode}：同一节点累加、不同节点独立计数；</li>
 *   <li>{@code mergeTakesElementwiseMax}：合并按分量取 max，并纳入仅入参方持有的分量；</li>
 *   <li>{@code mergeDoesNotMutateOther}：merge 只更新接收方，不改动被合并的入参时钟；</li>
 *   <li>{@code emptyClockIsDominatedByAnyClock}：空时钟被任意非空时钟支配（AFTER/BEFORE）；</li>
 *   <li>{@code equalClocksCompareEqual}：全分量相等 → EQUAL；</li>
 *   <li>{@code disjointClocksAreConcurrent}：分量集合互不相交 → CONCURRENT；</li>
 *   <li>{@code partialOverlapIsConcurrent}：部分重叠且各有占优分量 → CONCURRENT；</li>
 *   <li>{@code jsonRoundTrip}：toJson → fromJson 往返后语义等价（EQUAL）；</li>
 *   <li>{@code fromJsonHandlesNullAndBlank}：null/空白输入返回空时钟而非抛异常。</li>
 * </ul>
 *
 * @author zhanjh
 * @since 0.0.1
 */
class VectorClockTest {

    /**
     * 验证点：increment 按节点累加——node-1 连续自增两次得 2，node-2 自增一次得 1。
     */
    @Test
    void incrementAccumulatesPerNode() {
        VectorClock clock = new VectorClock();
        clock.increment("node-1").increment("node-1").increment("node-2");
        assertThat(clock.snapshot()).containsEntry("node-1", 2L).containsEntry("node-2", 1L);
    }

    /**
     * 验证点：merge 对每个分量取 max（node-1: max(3,1)=3、node-2: max(1,4)=4），
     * 并纳入仅入参方持有的 node-3=2。
     */
    @Test
    void mergeTakesElementwiseMax() {
        VectorClock a = new VectorClock(Map.of("node-1", 3L, "node-2", 1L));
        VectorClock b = new VectorClock(Map.of("node-1", 1L, "node-2", 4L, "node-3", 2L));
        a.merge(b);
        assertThat(a.snapshot()).containsEntry("node-1", 3L)
                .containsEntry("node-2", 4L)
                .containsEntry("node-3", 2L);
    }

    /**
     * 验证点：空时钟被任意非空时钟支配——compare(some, empty)=AFTER，反向比较为 BEFORE。
     */
    @Test
    void emptyClockIsDominatedByAnyClock() {
        VectorClock empty = new VectorClock();
        VectorClock some = new VectorClock().increment("node-1");
        assertThat(VectorClock.compare(some, empty)).isEqualTo(ClockRelation.AFTER);
        assertThat(VectorClock.compare(empty, some)).isEqualTo(ClockRelation.BEFORE);
    }

    /**
     * 验证点：全部分量相等的两个时钟判为 EQUAL。
     */
    @Test
    void equalClocksCompareEqual() {
        VectorClock a = new VectorClock(Map.of("node-1", 2L));
        VectorClock b = new VectorClock(Map.of("node-1", 2L));
        assertThat(VectorClock.compare(a, b)).isEqualTo(ClockRelation.EQUAL);
    }

    /**
     * 验证点：双方分量集合互不相交（node-1 与 node-sim）时互不支配，判为 CONCURRENT。
     */
    @Test
    void disjointClocksAreConcurrent() {
        VectorClock a = new VectorClock().increment("node-1");
        VectorClock b = new VectorClock().increment("node-sim");
        assertThat(VectorClock.isConcurrent(a, b)).isTrue();
        assertThat(VectorClock.compare(a, b)).isEqualTo(ClockRelation.CONCURRENT);
    }

    /**
     * 验证点：部分重叠且双方各有占优分量（a 在 node-1 占优、b 独有 node-3 且更大）→ 判为 CONCURRENT。
     */
    @Test
    void partialOverlapIsConcurrent() {
        VectorClock a = new VectorClock(Map.of("node-1", 3L, "node-2", 1L));
        VectorClock b = new VectorClock(Map.of("node-1", 1L, "node-3", 5L));
        // node-1: 3>1（a 占优）与 node-3: 5>0（b 占优）同时成立 → 互不支配
        assertThat(VectorClock.compare(a, b)).isEqualTo(ClockRelation.CONCURRENT);
    }

    /**
     * 验证点：toJson → fromJson 往返后与原时钟语义等价（判为 EQUAL）。
     */
    @Test
    void jsonRoundTrip() {
        VectorClock clock = new VectorClock().increment("node-1").increment("node-1").increment("node-2");
        VectorClock restored = VectorClock.fromJson(clock.toJson());
        assertThat(VectorClock.compare(clock, restored)).isEqualTo(ClockRelation.EQUAL);
    }

    /**
     * 验证点：fromJson 对 null 与空白串均返回空时钟（isEmpty 为 true），而非抛异常。
     */
    @Test
    void fromJsonHandlesNullAndBlank() {
        assertThat(VectorClock.fromJson(null).isEmpty()).isTrue();
        assertThat(VectorClock.fromJson("  ").isEmpty()).isTrue();
    }

    /**
     * 验证点：a.merge(b) 只更新接收方 a，入参 b 的分量保持不变（仍仅含 node-2）。
     */
    @Test
    void mergeDoesNotMutateOther() {
        VectorClock a = new VectorClock(Map.of("node-1", 3L));
        VectorClock b = new VectorClock(Map.of("node-2", 4L));
        a.merge(b);
        assertThat(b.snapshot()).containsOnlyKeys("node-2");
    }
}
