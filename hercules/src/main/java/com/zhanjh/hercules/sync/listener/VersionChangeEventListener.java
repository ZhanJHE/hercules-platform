package com.zhanjh.hercules.sync.listener;

import com.zhanjh.hercules.sync.consumer.VersionChangeConsumer;
import com.zhanjh.hercules.sync.model.VersionChangeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 事务同步桥：把进程内版本变更事件转交消费者的 AFTER_COMMIT 监听器。
 *
 * <p>@TransactionalEventListener(phase = AFTER_COMMIT) 保证业务事务先提交、缓存同步后执行，
 * 避免消费者读到未提交数据或用回滚数据刷新缓存；fallbackExecution = true 使
 * 无事务上下文的调用（如 DebugController 调试端点、非事务方法）退化为「发布即执行」，
 * 调试端点依赖该行为立即看到同步结果。</p>
 *
 * <p>异常策略：消费过程抛出的任何异常都在此捕获并记 error 日志，不再向上传播——
 * 此时业务事务已提交、响应已确定，同步失败不应影响已提交的业务结果；
 * 代价是失败事件不重试（MVP 取舍，Sprint 2 由 RocketMQ 的重试机制弥补）。</p>
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Component
public class VersionChangeEventListener {

    /** 同步失败统一在此记 error 日志（含 cache_key 上下文），便于排查且不影响已提交的业务响应。 */
    private static final Logger log = LoggerFactory.getLogger(VersionChangeEventListener.class);

    /** 版本变更消费者，事件的实际处理者（冲突消解决策表在其内实现）。 */
    private final VersionChangeConsumer consumer;

    public VersionChangeEventListener(VersionChangeConsumer consumer) {
        this.consumer = consumer;
    }

    /**
     * 事务提交后（或无事务上下文时立即）回调：把事件载荷转交消费者。
     *
     * <p>实现要点：catch (Exception) 兜底捕获全部异常并记 error 日志后返回，
     * 不向事件多播器抛出，保证同步链路失败不影响已提交的业务响应。</p>
     *
     * @param event 版本变更事件（由 InProcessEventBusProducer 发布），不应为 null
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(VersionChangeEvent event) {
        try {
            consumer.onMessage(event.value());
        } catch (Exception e) {
            // 事务已提交、业务响应已确定：吞掉异常仅记日志，避免同步失败污染业务结果
            log.error("[hercules-sync] version sync failed for key={}", event.value().key(), e);
        }
    }
}
