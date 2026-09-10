package com.zhanjh.hercules.agent.core;

import com.zhanjh.hercules.agent.orchestrator.Intent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 意图路由智能体（阶段 D）：两级路由——关键词规则优先（覆盖高频意图，零 token、低延迟），
 * 未命中才走 LLM 分类兜底（要求模型只输出 JSON），解析失败降级为 RECOMMEND。
 *
 * <p>规则优先级（顺序敏感）：CONFIRM > ENROLL > RECOMMEND。确认词最优先，因为推荐流程
 * 的收尾提示是「回复：确认」，用户后续消息通常以确认词开头；若先匹配选课动词会产生误判。
 *
 * <p>LLM 兜底仅在本消息无法规则命中时触发一次（complete，非流式）；
 * LLM 异常/超时不抛出——按 UNKNOWN 的降级语义返回 RECOMMEND，对话入口永不因路由失败中断。
 *
 * <p>线程安全性：无实例可变状态（关键词表为常量），可并发调用。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Component
public class RoutingAgent {

    private static final Logger log = LoggerFactory.getLogger(RoutingAgent.class);

    /** LLM 分类输出格式的系统提示：只允许输出 JSON。 */
    private static final String CLASSIFY_SYSTEM_PROMPT =
            "你是选课平台的意图分类器。只输出一个 JSON 对象，格式：{\"intent\":\"RECOMMEND|ENROLL|CONFIRM|UNKNOWN\"}。"
                    + "RECOMMEND=查询/推荐课程；ENROLL=请求选某门课；CONFIRM=对上一步的确认回复；UNKNOWN=闲聊或无关。不要输出任何其他文字。";

    /** 确认词（优先级最高，出现即 CONFIRM）。 */
    private static final String[] CONFIRM_WORDS = {"确认", "确定", "是的", "好的", "没问题", "就选这个", "执行"};

    /** 选课动作词（出现即 ENROLL）。 */
    private static final String[] ENROLL_WORDS = {"帮我选", "我要选", "给我选", "我想选", "选一下", "帮我报", "选课"};

    /** 推荐/查询词（出现即 RECOMMEND）。 */
    private static final String[] RECOMMEND_WORDS = {
            "推荐", "建议", "有什么课", "找课", "选什么", "有哪些", "查一下", "查询", "看看", "搜索", "多少学分", "什么课"};

    /** LLM 端口（分类兜底）。 */
    private final LlmPort llm;

    public RoutingAgent(LlmPort llm) {
        this.llm = llm;
    }

    /**
     * 意图分类：规则表 → LLM 兜底 → 降级 RECOMMEND。永不抛出异常。
     *
     * @param message 用户消息，不应为 null
     * @return 意图（UNKNOWN 仅在 LLM 明确判定时返回，解析失败一律降级 RECOMMEND）
     */
    public Intent classify(String message) {
        String text = message == null ? "" : message.trim();
        for (String word : CONFIRM_WORDS) {
            if (text.contains(word)) {
                return Intent.CONFIRM;
            }
        }
        for (String word : ENROLL_WORDS) {
            if (text.contains(word)) {
                return Intent.ENROLL;
            }
        }
        for (String word : RECOMMEND_WORDS) {
            if (text.contains(word)) {
                return Intent.RECOMMEND;
            }
        }
        return classifyByLlm(text);
    }

    /**
     * LLM 兜底分类：只输出 JSON，宽松解析（按子串匹配意图名）；任何异常降级 RECOMMEND。
     *
     * @param text 用户消息
     * @return 意图（解析失败 → RECOMMEND）
     */
    private Intent classifyByLlm(String text) {
        try {
            String raw = llm.complete(CLASSIFY_SYSTEM_PROMPT, text);
            if (raw == null || raw.isBlank()) {
                return Intent.RECOMMEND;
            }
            if (raw.contains("ENROLL")) {
                return Intent.ENROLL;
            }
            if (raw.contains("CONFIRM")) {
                return Intent.CONFIRM;
            }
            if (raw.contains("RECOMMEND")) {
                return Intent.RECOMMEND;
            }
            return raw.contains("UNKNOWN") ? Intent.UNKNOWN : Intent.RECOMMEND;
        } catch (Exception e) {
            // 路由是入口：LLM 兜底失败绝不中断对话，降级为推荐链（检索 + 提示）
            log.warn("[hercules-agent] intent classify via LLM failed, fallback to RECOMMEND: {}", e.getMessage());
            return Intent.RECOMMEND;
        }
    }

    /**
     * 从选课消息中提取课程线索（路由器的静态工具，编排器在 ENROLL 分支调用）。
     *
     * <p>识别顺序：数字主键（如「选 1」）→ 课程编码（如 CS101）→ 书名号名称（《数据结构》）→
     * 剥离动词后的剩余文本（如「帮我选人工智能导论」→「人工智能导论」）。
     * 多于一种线索时优先级从左到右（主键最精确）。
     *
     * @param message 用户消息
     * @return 课程线索；无法提取时为 null（由调用方追问）
     */
    public CourseRef extractCourseRef(String message) {
        String text = message == null ? "" : message.trim();
        java.util.regex.Matcher idMatcher = java.util.regex.Pattern
                .compile("(?:选|报|课程)\\s*([0-9]{1,6})(?![0-9])").matcher(text);
        if (idMatcher.find()) {
            return new CourseRef(Long.parseLong(idMatcher.group(1)), null, null);
        }
        java.util.regex.Matcher codeMatcher = java.util.regex.Pattern
                .compile("([A-Za-z]{2,}[0-9]{2,4})").matcher(text);
        if (codeMatcher.find()) {
            return new CourseRef(null, codeMatcher.group(1).toUpperCase(), null);
        }
        java.util.regex.Matcher nameMatcher = java.util.regex.Pattern
                .compile("《(.+?)》").matcher(text);
        if (nameMatcher.find()) {
            return new CourseRef(null, null, nameMatcher.group(1).trim());
        }
        // 剥离常见动词/语气词后的剩余文本作为课程名线索
        String stripped = text
                .replaceAll("帮我选|我要选|给我选|我想选|选一下|帮我报|确认选课|确认|选|报", "")
                .replace("《", "").replace("》", "").trim();
        if (stripped.length() >= 2 && stripped.length() <= 30) {
            return new CourseRef(null, null, stripped);
        }
        return null;
    }

    /**
     * 课程线索：三种定位方式（主键 / 编码 / 名称），至多其一非空。
     *
     * @param id   课程主键（消息中出现数字时）
     * @param code 课程编码（如 CS101）
     * @param name 课程名称（书名号或剥离动词后的文本）
     * @author zhanjh
     * @since 0.0.1
     */
    public record CourseRef(Long id, String code, String name) {
    }
}
