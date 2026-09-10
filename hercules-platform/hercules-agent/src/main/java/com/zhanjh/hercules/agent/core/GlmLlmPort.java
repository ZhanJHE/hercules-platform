package com.zhanjh.hercules.agent.core;

import com.zhanjh.hercules.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.stream.Collectors;

/**
 * LLM 端口的 GLM 实现（阶段 D）：封装 Spring AI {@link ChatClient}，
 * 经 OpenAI 兼容协议调用智谱 open.bigmodel.cn /api/paas/v4/chat/completions（glm-4.5-air）。
 *
 * <p>模型名/温度/max-tokens 由 spring.ai.openai.chat.options 配置承载（Spring AI 官方键），
 * 本类不重复配置。超时统一在 reactor 链上以 {@code timeout()} 施加（阻塞调用 = 流式聚合 + block），
 * 避免分别配置 RestClient/ WebClient 超时的双份维护。
 *
 * <p>装配：{@code hercules.agent.enabled=true}（默认）时由组件扫描注册；
 * 测试环境由 TestStubsConfig 以 {@link StubLlmPort} @Primary 压过本类。
 *
 * <p>线程安全性：ChatClient 为无状态线程安全组件，可并发调用。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Component
@ConditionalOnProperty(name = "hercules.agent.enabled", havingValue = "true", matchIfMissing = true)
public class GlmLlmPort implements LlmPort {

    /** 日志记录器（流式调用关键节点观测：订阅/首片/分片数/完成/错误）。 */
    private static final Logger log = LoggerFactory.getLogger(GlmLlmPort.class);

    /** Spring AI 聊天客户端（模型参数经 yml 的 options 注入）。 */
    private final ChatClient chatClient;

    /** 编排配置（超时秒数）。 */
    private final AgentProperties props;

    public GlmLlmPort(ChatClient.Builder chatClientBuilder, AgentProperties props) {
        this.chatClient = chatClientBuilder.build();
        this.props = props;
    }

    /**
     * 阻塞补全：流式聚合 + 整体超时（对齐 WebFlux 侧超时纪律）。
     *
     * @param systemPrompt 系统提示
     * @param userPrompt   用户输入
     * @return 输出全文
     */
    @Override
    public String complete(String systemPrompt, String userPrompt) {
        return stream(systemPrompt, userPrompt)
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .collect(Collectors.joining())
                .block();
    }

    /**
     * 流式补全：内容分片流（GLM 思考型模型的 reasoning_content 由 Spring AI DTO 过滤，
     * 只透出正文 content；token 上限经 yml max-tokens 控制，确保思考后仍有余量输出正文）。
     * 关键节点日志（订阅/首片/分片数/完成/错误）供端到端排障。
     *
     * @param systemPrompt 系统提示
     * @param userPrompt   用户输入
     * @return 内容分片流
     */
    @Override
    public Flux<String> stream(String systemPrompt, String userPrompt) {
        long start = System.currentTimeMillis();
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .stream()
                .content()
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .doOnSubscribe(s -> log.info("[hercules-agent] llm stream subscribed"))
                .doOnNext(t -> {
                    if (chunks.incrementAndGet() == 1) {
                        log.info("[hercules-agent] llm first chunk after {}ms", System.currentTimeMillis() - start);
                    }
                })
                .doOnError(e -> log.error("[hercules-agent] llm stream error after {}ms: {}",
                        System.currentTimeMillis() - start, e.toString()))
                .doOnComplete(() -> log.info("[hercules-agent] llm stream complete: {} chunks, {}ms",
                        chunks.getAndSet(0), System.currentTimeMillis() - start));
    }

    /** 分片计数器（单流内的观测计数，流完成时归零；观测用途，非精确并发语义）。 */
    private final java.util.concurrent.atomic.AtomicInteger chunks = new java.util.concurrent.atomic.AtomicInteger();
}
