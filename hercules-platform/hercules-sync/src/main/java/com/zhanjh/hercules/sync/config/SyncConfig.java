package com.zhanjh.hercules.sync.config;

import com.zhanjh.hercules.sync.convert.BinlogToVersionConverter;
import com.zhanjh.hercules.sync.listener.RocketMQBinlogListener;
import com.zhanjh.hercules.sync.producer.NoopVersionChangeProducer;
import com.zhanjh.hercules.sync.producer.VersionChangeProducer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 同步模块装配配置：注册本模块的配置属性 Bean，并按 hercules.sync.transport 装配传输层。
 *
 * <p>传输装配（阶段 B）：
 * <ul>
 *   <li><b>in-process（默认，matchIfMissing）</b>：不注册任何附加 Bean——
 *       {@code InProcessEventBusProducer}（@Component）是唯一的 VersionChangeProducer
 *       候选，业务写路径经它 + AFTER_COMMIT 监听器完成「先提交、后同步」；</li>
 *   <li><b>canal-mq</b>：注册 {@code @Primary} 的 {@link NoopVersionChangeProducer}
 *       （业务写路径的事务内发布失效，同步触发源移交 binlog 链路）与
 *       {@link RocketMQBinlogListener}（SmartLifecycle，随容器启停 RocketMQ 消费者）；
 *       进程内总线仍保留，供演示钩子（DebugController 按具体类型注入）直接发布模拟版本。</li>
 * </ul>
 *
 * <p>组件扫描说明：该类位于 com.zhanjh.hercules.sync.config 包下，处于启动类
 * com.zhanjh.hercules 的默认扫描范围内，无需额外导入。
 *
 * <p>线程安全性：仅承载 Bean 注册，无可变状态。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Configuration
@EnableConfigurationProperties(HerculesSyncProperties.class)
public class SyncConfig {

    /**
     * canal-mq 模式的业务写路径注入点：空发布器（@Primary 压过进程内总线候选）。
     *
     * <p>业务事务内的 publish 变为无操作，缓存同步完全由 MySQL Binlog → canal → RocketMQ
     * → 消费者链路驱动（写路径削峰：省掉事务内的课程重读与存量时钟加载）。
     *
     * @return 空发布器实例
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "hercules.sync.transport", havingValue = HerculesSyncProperties.TRANSPORT_CANAL_MQ)
    public VersionChangeProducer noopVersionChangeProducer() {
        return new NoopVersionChangeProducer();
    }

    /**
     * canal-mq 模式的 RocketMQ 消费者：订阅 canal 投递的 binlog topic，
     * 顺序消费并转交转换器（SmartLifecycle 随容器启停）。
     *
     * @param syncProps 同步配置（name-server/topic/消费组/节点名）
     * @param converter flatMessage → VersionedValue 转换器
     * @return 消费者生命周期 Bean
     */
    @Bean
    @ConditionalOnProperty(name = "hercules.sync.transport", havingValue = HerculesSyncProperties.TRANSPORT_CANAL_MQ)
    public RocketMQBinlogListener rocketMQBinlogListener(HerculesSyncProperties syncProps,
                                                         BinlogToVersionConverter converter) {
        return new RocketMQBinlogListener(syncProps, converter);
    }
}
