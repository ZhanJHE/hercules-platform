package com.zhanjh.hercules.agent.core;

import com.zhanjh.hercules.agent.orchestrator.Intent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RoutingAgent 单元测试（阶段 D）：关键词规则优先级、LLM 兜底分类、LLM 失败降级、课程线索提取。
 *
 * @author zhanjh
 * @since 0.0.1
 */
class RoutingAgentTest {

    /** 脚本化 LLM 桩。 */
    private final StubLlmPort llm = new StubLlmPort();

    /** 被测对象。 */
    private final RoutingAgent routingAgent = new RoutingAgent(llm);

    /**
     * 验证点：关键词规则优先级——CONFIRM > ENROLL > RECOMMEND，规则命中不消耗 LLM。
     */
    @Test
    void keywordRulesTakePriorityOverLlm() {
        assertThat(routingAgent.classify("确认")).isEqualTo(Intent.CONFIRM);
        assertThat(routingAgent.classify("帮我选 CS101")).isEqualTo(Intent.ENROLL);
        assertThat(routingAgent.classify("推荐几门 3 学分的课")).isEqualTo(Intent.RECOMMEND);
        assertThat(llm.consumed()).isZero();
    }

    /**
     * 验证点：规则未命中 → LLM 兜底 JSON 分类（宽松子串解析）。
     */
    @Test
    void llmFallbackClassifiesWhenRulesMiss() {
        llm.enqueue("{\"intent\": \"ENROLL\"}");
        assertThat(routingAgent.classify("把那门数据库加到我的课表")).isEqualTo(Intent.ENROLL);
        llm.enqueue("前置文字 {\"intent\":\"UNKNOWN\"} 后缀文字");
        assertThat(routingAgent.classify("今天天气怎么样")).isEqualTo(Intent.UNKNOWN);
    }

    /**
     * 验证点：LLM 兜底异常/输出异常 → 降级 RECOMMEND，绝不抛出（对话入口永不因路由失败中断）。
     */
    @Test
    void llmFailureFallsBackToRecommend() {
        LlmPort broken = new LlmPort() {
            @Override
            public String complete(String systemPrompt, String userPrompt) {
                throw new IllegalStateException("网络不通");
            }

            @Override
            public reactor.core.publisher.Flux<String> stream(String systemPrompt, String userPrompt) {
                throw new IllegalStateException("网络不通");
            }
        };
        RoutingAgent agent = new RoutingAgent(broken);
        assertThat(agent.classify("一句完全不匹配任何规则的输入")).isEqualTo(Intent.RECOMMEND);
    }

    /**
     * 验证点：课程线索提取——主键 / 编码 / 书名号名称 / 剥离动词后的剩余文本。
     */
    @Test
    void extractCourseRefSupportsIdCodeAndName() {
        assertThat(routingAgent.extractCourseRef("帮我选 1").id()).isEqualTo(1L);
        assertThat(routingAgent.extractCourseRef("帮我选课程 39").id()).isEqualTo(39L);
        assertThat(routingAgent.extractCourseRef("我要选 CS101").code()).isEqualTo("CS101");
        assertThat(routingAgent.extractCourseRef("帮我选《数据结构》").name()).isEqualTo("数据结构");
        assertThat(routingAgent.extractCourseRef("帮我选人工智能导论").name()).isEqualTo("人工智能导论");
        assertThat(routingAgent.extractCourseRef("帮我选")).isNull();
    }
}
