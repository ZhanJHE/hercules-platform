package com.zhanjh.hercules.sync.clock;

/**
 * 向量时钟比较结果枚举：分布式写因果/并发关系的四值判定。
 *
 * <p>由 {@link VectorClock#compare(VectorClock, VectorClock)} 逐分量比较两个向量时钟后产生，
 * 是版本变更消费端（VersionChangeConsumer）冲突消解决策表的核心判据：
 * AFTER 直接应用新值；EQUAL / BEFORE 判定为重复或过期消息而丢弃（幂等）；
 * CONCURRENT 表示两个节点互不支配的并发写，交由
 * {@link com.zhanjh.hercules.sync.resolver.ConflictMergeStrategy} 做字段级 LWW 合并，
 * 对应起步文档 FR-HC-03 的并发冲突消解与风险 R-03 场景。</p>
 *
 * @author zhanjh
 * @since 0.0.1
 */
public enum ClockRelation {
    /** 两时钟全等：所有分量逐一相等（含两侧均为空时钟），消费端视为重复消息直接丢弃。 */
    EQUAL,
    /** a 发生在 b 之前（b 支配 a）：b 的所有分量 >= a 且至少一个更大，a 为过期旧版本，消费端丢弃。 */
    BEFORE,
    /** a 发生在 b 之后（a 支配 b）：a 的所有分量 >= b 且至少一个更大，a 为可安全应用的新版本。 */
    AFTER,
    /** a 与 b 并发：互有分量更大、谁也不支配谁，属潜在写冲突（风险 R-03），必须走冲突合并策略裁决。 */
    CONCURRENT
}
