package com.zhanjh.hercules.sync.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 同步模块装配配置：注册本模块的配置属性 Bean。
 *
 * <p>多模块化后模块间配置解耦：hercules-sync 自己注册 HerculesSyncProperties
 * （hercules.sync.node-id / max-names 等键），不再由缓存模块的 CacheConfig 代劳；
 * 后续接入 Canal/RocketMQ（Sprint 2）时，传输适配器的装配也归入本配置类。
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
}
