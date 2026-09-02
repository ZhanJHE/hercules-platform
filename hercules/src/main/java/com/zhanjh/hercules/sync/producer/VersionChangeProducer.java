package com.zhanjh.hercules.sync.producer;

import com.zhanjh.hercules.sync.model.VersionedValue;

/**
 * 版本变更生产者接口：业务写路径与缓存同步传输层之间的抽象边界。
 *
 * <p>MVP 实现为 {@link InProcessEventBusProducer}（Spring 进程内事件总线）；
 * Sprint 2 由「Canal 监听 Binlog → RocketMQ 顺序消息」的适配器实现同一接口替换，
 * 业务调用方（如 EnrollmentService、DebugController）无需改动。</p>
 *
 * <p>调用契约：典型用法是在 @Transactional 业务方法内、数据落库之后调用
 * {@link #publish(VersionedValue)}；真正驱动消费者的是事务提交钩子
 * （AFTER_COMMIT 监听器），从而保证「先提交、后同步」。载荷中的时间戳与向量时钟
 * 由调用方负责构建（存量时钟 + 本节点自增，见 VersionReader）。</p>
 *
 * @author zhanjh
 * @since 0.0.1
 */
public interface VersionChangeProducer {

    /**
     * 发布一条版本变更，交由底层传输层投递给消费端做冲突消解与缓存刷新。
     *
     * <p>实现契约：不应向调用方抛出未处理的传输层异常中断业务事务（MVP 实现中
     * 消费端异常由 AFTER_COMMIT 监听器兜底捕获；Sprint 2 实现应保证投递失败不回滚业务）。</p>
     *
     * @param value 版本化值（含缓存键、值 JSON、来源节点、时间戳与已自增本节点分量的向量时钟），不应为 null
     */
    void publish(VersionedValue value);
}
