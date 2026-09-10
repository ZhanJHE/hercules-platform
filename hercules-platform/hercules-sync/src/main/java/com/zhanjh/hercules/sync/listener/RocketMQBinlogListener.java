package com.zhanjh.hercules.sync.listener;

import com.zhanjh.hercules.sync.config.HerculesSyncProperties;
import com.zhanjh.hercules.sync.convert.BinlogToVersionConverter;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RocketMQ Binlog 消费者（阶段 B，canal-mq 传输模式专属 Bean）：封装
 * {@link DefaultMQPushConsumer}，把 canal 投递到 topic 的 flatMessage 消息转交
 * {@link BinlogToVersionConverter}。
 *
 * <p>消费语义（与同步链正确性直接相关）：
 * <ul>
 *   <li><b>顺序消费</b>（MessageListenerOrderly）：canal 侧 partitionHash 按「表+主键」
 *       把同一课程的变更固定到同一队列，顺序监听器锁定队列串行消费——单键顺序性与
 *       进程内总线的「发布线程串行」语义等价；</li>
 *   <li><b>消费线程 1</b>（ConsumeThreadMin/Max=1）：毕设规模下单线程足够，并彻底避免
 *       跨队列并发导致的同键乱序观察窗口；</li>
 *   <li><b>失败重试</b>：转换/消费抛异常 → 返回 SUSPEND_CURRENT_QUEUE_A_MOMENT 挂起重试
 *       （数据库瞬断时挂起队列而非跳过，保证不丢事件）；解析类毒消息由转换器内部跳过，
 *       不会进入重试循环；</li>
 *   <li><b>起始位点</b>：CONSUME_FROM_LAST_OFFSET——消费者只处理启动后的增量变更；
 *       存量数据一致性由巡检引擎（阶段 C）兜底，首次部署如需全量刷缓存可手动重置位点。</li>
 * </ul>
 *
 * <p>生命周期：实现 {@link SmartLifecycle}，随 Spring 容器启动/停止（start/stop 由
 * SyncConfig 在 canal-mq 模式下注册；in-process 模式下本类不会被装配）。
 *
 * <p>线程安全性：start/stop 由容器串行驱动；消费回调由 RocketMQ 客户端线程池调用，
 * 全部委托给无状态的转换器。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public class RocketMQBinlogListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RocketMQBinlogListener.class);

    /** 同步模块配置：name-server 地址、topic、消费组。 */
    private final HerculesSyncProperties syncProps;
    /** 转换器：flatMessage → VersionedValue → 消费者。 */
    private final BinlogToVersionConverter converter;

    /** RocketMQ 推模式消费者：start 时创建，stop 时关闭。 */
    private DefaultMQPushConsumer consumer;

    /** 生命周期状态标记（SmartLifecycle 契约）。 */
    private volatile boolean running;

    public RocketMQBinlogListener(HerculesSyncProperties syncProps, BinlogToVersionConverter converter) {
        this.syncProps = syncProps;
        this.converter = converter;
    }

    /**
     * 启动消费者：创建 DefaultMQPushConsumer → 订阅 topic → 注册顺序监听 → start。
     * name-server 不可达时 start 不抛出（客户端后台重连），消费延迟至连接恢复——
     * 与「同步链最终一致」的定位一致。
     */
    @Override
    public void start() {
        HerculesSyncProperties.RocketMQ config = syncProps.getRocketmq();
        consumer = new DefaultMQPushConsumer(config.getConsumerGroup());
        consumer.setNamesrvAddr(config.getNameServer());
        consumer.setConsumeThreadMin(1);
        consumer.setConsumeThreadMax(1);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        try {
            consumer.subscribe(config.getTopic(), "*");
            consumer.registerMessageListener((MessageListenerOrderly) this::consumeOrderly);
            consumer.start();
        } catch (Exception e) {
            // 订阅/启动失败属于配置级错误（topic 名非法等），不应带着坏消费者静默运行
            throw new IllegalStateException("RocketMQ binlog consumer start failed", e);
        }
        running = true;
        log.info("[hercules-sync] RocketMQ binlog consumer started (nameServer={}, topic={}, group={})",
                config.getNameServer(), config.getTopic(), config.getConsumerGroup());
    }

    /**
     * 停止消费者（容器关闭时调用，释放与 name-server/broker 的连接）。
     */
    @Override
    public void stop() {
        running = false;
        if (consumer != null) {
            consumer.shutdown();
            log.info("[hercules-sync] RocketMQ binlog consumer stopped");
        }
    }

    /** 容器启动即拉起消费者（canal-mq 模式下同步链为核心组件）。 */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 顺序消费回调：逐条解析 flatMessage 并转交转换器；转换异常挂起队列稍后重试，
     * 解析毒消息（转换器抛出 JsonProcessingException）记 error 后跳过——坏消息不应阻塞整条队列。
     *
     * @param messages 本批消息（顺序监听下同队列串行）
     * @param context  顺序消费上下文（使用默认挂起间隔）
     * @return SUCCESS-本批全部处理成功；SUSPEND-转换/消费异常，稍后重试
     */
    private ConsumeOrderlyStatus consumeOrderly(List<MessageExt> messages, ConsumeOrderlyContext context) {
        for (MessageExt message : messages) {
            String flatMessageJson = new String(message.getBody(), StandardCharsets.UTF_8);
            try {
                int published = converter.onFlatMessage(flatMessageJson);
                if (published > 0) {
                    log.debug("[hercules-sync] binlog message applied rows={} (msgId={})", published, message.getMsgId());
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                // 毒消息：JSON 永远解析失败，跳过并保留现场日志（含消息 ID 便于排查）
                log.error("[hercules-sync] skip malformed flatMessage (msgId={})", message.getMsgId(), e);
            } catch (Exception e) {
                // 数据库瞬断等可恢复异常：挂起当前队列稍后重试（不丢事件，顺序保持）
                log.warn("[hercules-sync] consume failed, suspend queue for retry (msgId={})", message.getMsgId(), e);
                return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
            }
        }
        return ConsumeOrderlyStatus.SUCCESS;
    }
}
