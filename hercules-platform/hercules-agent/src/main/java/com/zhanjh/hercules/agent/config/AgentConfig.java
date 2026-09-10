package com.zhanjh.hercules.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 多智能体模块装配配置（阶段 D）：注册 hercules.agent.* 配置属性。
 *
 * <p>Spring AI 的 OpenAI 客户端（ChatClient.Builder）由 starter 自动装配，
 * 模型参数经 spring.ai.openai.chat.options 配置承载；本配置类只注册编排行为参数。
 *
 * <p>组件扫描说明：位于 com.zhanjh.hercules.agent.config 包，处于启动类默认扫描范围。
 *
 * <p>线程安全性：仅承载 Bean 注册，无可变状态。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfig {
}
