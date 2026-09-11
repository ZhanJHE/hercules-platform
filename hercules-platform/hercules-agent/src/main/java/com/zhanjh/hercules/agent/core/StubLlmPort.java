package com.zhanjh.hercules.agent.core;

import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM 端口的脚本化桩（阶段 D）：测试与演示降级用，按入队顺序回放预设响应。
 *
 * <p>用法：测试里 `stub.enqueue("响应A", "响应B")` 后注入被测对象；complete 按序弹出，
 * stream 把全文按固定 8 字符切片推送（模拟分片）。队列耗尽时回放最后一个响应
 * （避免多场景测试因次数预估失误而 flaky）。
 *
 * <p>线程安全性：响应队列经 synchronized 保护。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public class StubLlmPort implements LlmPort {

    /** 预设响应队列（按序回放）。 */
    private final Deque<String> scripted = new ArrayDeque<>();

    /** 已消耗的响应计数（观测用）。 */
    private final AtomicInteger consumed = new AtomicInteger();

    /** 流式首包延迟（毫秒，默认 0）：供测试验证 LLM 耗时计量（t_agent_trace.llm_latency_ms）。 */
    private volatile long streamDelayMillis;

    /**
     * 设置流式首包延迟（毫秒）：只在流的开头延迟，后续分片仍同步发射，
     * 避免调度器重排引入测试不确定性。
     *
     * @param millis 延迟毫秒数，负值按 0 处理
     */
    public void setStreamDelayMillis(long millis) {
        this.streamDelayMillis = Math.max(0L, millis);
    }

    /**
     * 入队预设响应（可变参数按序回放）。
     *
     * @param responses 预设响应文本
     */
    public void enqueue(String... responses) {
        synchronized (scripted) {
            for (String r : responses) {
                scripted.addLast(r);
            }
        }
    }

    /**
     * 已消耗响应数（测试断言用）。
     *
     * @return 消耗计数
     */
    public int consumed() {
        return consumed.get();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        return next();
    }

    @Override
    public Flux<String> stream(String systemPrompt, String userPrompt) {
        String response = next();
        List<String> slices = new java.util.ArrayList<>();
        for (int i = 0; i < response.length(); i += 8) {
            slices.add(response.substring(i, Math.min(response.length(), i + 8)));
        }
        Flux<String> flux = Flux.fromIterable(slices);
        return streamDelayMillis > 0
                ? flux.delaySubscription(java.time.Duration.ofMillis(streamDelayMillis))
                : flux;
    }

    /**
     * 按序取响应；队列空时回放最近一条（无历史则返回固定占位文本）。
     *
     * @return 预设响应
     */
    private String next() {
        consumed.incrementAndGet();
        synchronized (scripted) {
            if (!scripted.isEmpty()) {
                return scripted.pollFirst();
            }
            return scripted.peekLast() == null ? "(stub)" : scripted.peekLast();
        }
    }
}
