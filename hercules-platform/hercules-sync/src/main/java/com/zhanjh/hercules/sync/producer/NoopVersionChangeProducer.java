package com.zhanjh.hercules.sync.producer;

import com.zhanjh.hercules.sync.model.VersionedValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 空发布器（阶段 B，canal-mq 传输模式专属）：业务写路径的 {@link VersionChangeProducer}
 * 注入点占位——此时缓存同步的触发源是 MySQL Binlog（canal → RocketMQ → 消费者），
 * 应用事务内不再构建/发布版本事件（消除事务内的重读与时钟加载，写路径削峰）。
 *
 * <p>注入语义：由 SyncConfig 在 transport=canal-mq 时注册为 {@code @Primary} Bean，
 * 业务侧（EnrollmentService 等）无需感知传输切换；in-process 模式下本类不被装配。
 * 进程内事件总线仍无条件保留，供演示钩子（DebugController）直接使用。
 *
 * <p>防御性行为：publish 记 debug 日志后丢弃——若调用方在 canal 模式下仍尝试发布
 * （代码遗漏），日志可暴露，而非静默丢失。
 *
 * <p>线程安全性：无状态。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public class NoopVersionChangeProducer implements VersionChangeProducer {

    private static final Logger log = LoggerFactory.getLogger(NoopVersionChangeProducer.class);

    /**
     * 空实现：canal-mq 模式下版本事件由 binlog 链路产生，应用侧发布动作不生效。
     *
     * @param value 版本化值（被忽略，仅记 debug 日志）
     */
    @Override
    public void publish(VersionedValue value) {
        log.debug("[hercules-sync] canal-mq transport: skip in-app publish for key={} (binlog path owns versioning)",
                value.key());
    }
}
