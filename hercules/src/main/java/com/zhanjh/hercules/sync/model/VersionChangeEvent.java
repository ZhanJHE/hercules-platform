package com.zhanjh.hercules.sync.model;

/**
 * 进程内版本变更事件：MVP 传输层（Spring 事件总线）的消息信封。
 *
 * <p>由 InProcessEventBusProducer 包装 {@link VersionedValue} 后经 ApplicationEventPublisher 发布，
 * 由 VersionChangeEventListener 以 AFTER_COMMIT 阶段接收并转交 VersionChangeConsumer，
 * 从而保证「业务事务先提交、缓存同步后执行」。Sprint 2 用 Canal 监听 Binlog +
 * RocketMQ 顺序消息替换传输层时，仅替换生产/消费两端的适配器，本载荷结构保持不变。</p>
 *
 * @param value 变更的版本化值（含缓存键、值 JSON、来源节点、时间戳与向量时钟），不应为 null
 * @author zhanjh
 * @since 0.0.1
 */
public record VersionChangeEvent(VersionedValue value) {
}
