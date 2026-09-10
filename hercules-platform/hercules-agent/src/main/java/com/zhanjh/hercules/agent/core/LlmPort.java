package com.zhanjh.hercules.agent.core;

import reactor.core.publisher.Flux;

/**
 * LLM 端口（阶段 D）：多智能体模块对大模型的唯一依赖边界。
 *
 * <p>抽象动机：①测试零外部依赖——单测/集成测试注入 {@link StubLlmPort}（脚本化响应），
 * `mvnw test` 不触网；②传输可替换——当前实现为 Spring AI OpenAI 兼容客户端（GLM），
 * 未来换服务商只改适配器；③降级语义集中在调用方（编排器），端口本身只暴露两种调用形态。
 *
 * <p>两个方法：
 * <ul>
 *   <li>{@link #complete}：阻塞聚合——意图分类等短交互；</li>
 *   <li>{@link #stream}：流式——推荐解释等面向用户的生成，SSE 逐 token 下发。</li>
 * </ul>
 *
 * <p>实现契约：超时/网络异常以 onError 信号/异常形式向上传播，由调用方决定降级；
 * 端口不做重试（对话场景重试意义有限且加倍延迟）。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public interface LlmPort {

    /**
     * 阻塞式补全：聚合全部输出后返回全文。
     *
     * @param systemPrompt 系统提示（人设/输出格式约束）
     * @param userPrompt   用户侧输入（已含业务上下文）
     * @return 模型输出全文
     */
    String complete(String systemPrompt, String userPrompt);

    /**
     * 流式补全：按 token 分片推送。
     *
     * @param systemPrompt 系统提示
     * @param userPrompt   用户侧输入
     * @return 内容分片流
     */
    Flux<String> stream(String systemPrompt, String userPrompt);
}
