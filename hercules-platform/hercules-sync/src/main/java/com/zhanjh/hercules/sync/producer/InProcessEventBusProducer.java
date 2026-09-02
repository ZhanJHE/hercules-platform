package com.zhanjh.hercules.sync.producer;

import com.zhanjh.hercules.sync.model.VersionChangeEvent;
import com.zhanjh.hercules.sync.model.VersionedValue;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * MVP 传输实现：基于 Spring ApplicationEvent 的进程内事件总线生产者。
 *
 * <p>publish 时把 {@link VersionedValue} 包装成 {@link VersionChangeEvent} 同步发布，
 * 事件多播发生在发布线程；但真正触发消费的 VersionChangeEventListener 标注了
 * @TransactionalEventListener(AFTER_COMMIT)，因此消费动作被推迟到业务事务提交之后，
 * 实现「先提交、后同步」。Sprint 2 将以 Canal→RocketMQ 适配器实现
 * {@link VersionChangeProducer} 同接口替换本类。</p>
 *
 * <p>线程安全性：无实例可变状态（仅持有无状态的 publisher 引用），可被多线程并发调用。</p>
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Component
public class InProcessEventBusProducer implements VersionChangeProducer {

    /** Spring 事件发布器（构造注入），负责把版本变更事件多播给 AFTER_COMMIT 监听器。 */
    private final ApplicationEventPublisher publisher;

    public InProcessEventBusProducer(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * 同步发布版本变更事件。
     *
     * <p>实现要点：包装为 VersionChangeEvent 后直接调用
     * ApplicationEventPublisher#publishEvent，不做序列化、不经消息队列；
     * 无事务上下文时监听器因 fallbackExecution=true 仍会立即执行（调试端点依赖此行为）。</p>
     *
     * @param value 版本化值，调用方需保证非 null
     */
    @Override
    public void publish(VersionedValue value) {
        publisher.publishEvent(new VersionChangeEvent(value));
    }
}
