package com.zhanjh.hercules.sync.model;

import com.zhanjh.hercules.sync.clock.VectorClock;

/**
 * 携带向量时钟的版本化值：版本同步链路（生产者 → 事件/消息 → 消费者）的统一载荷。
 *
 * <p>生产侧（如 EnrollmentService）在业务事务内构建：valueJson 为库中最新数据的 JSON 快照，
 * clock 为「存量时钟 + 本节点自增」后的时钟；消费侧（VersionChangeConsumer）以 clock 与
 * 存量时钟的支配关系决策直接应用或冲突合并。MVP 中缓存值与向量时钟均以 JSON 字符串/结构承载，
 * 便于跨传输层（进程内事件总线 / Sprint 2 RocketMQ 顺序消息）序列化。</p>
 *
 * @param key       缓存键（如 CacheKeys.course(courseId)），同时是 t_cache_version.cache_key 的唯一业务键，不应为 null
 * @param valueJson 缓存值的 JSON 快照（整对象序列化），是字段级 LWW 合并的原料之一，不应为 null
 * @param nodeId    产生该版本的节点标识（对应 hercules.sync.node-id 配置值，也是时钟分量名），不应为 null
 * @param timestamp 版本产生时间（epoch millis，取 System.currentTimeMillis()；冲突合并时与旧值时间戳做 LWW 裁决）
 * @param clock     该版本的向量时钟，应已包含本节点自增后的分量，不应为 null
 * @author zhanjh
 * @since 0.0.1
 */
public record VersionedValue(String key, String valueJson, String nodeId, long timestamp, VectorClock clock) {
}
