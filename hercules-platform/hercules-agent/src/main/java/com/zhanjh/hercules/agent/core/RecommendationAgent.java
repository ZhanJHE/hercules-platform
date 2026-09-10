package com.zhanjh.hercules.agent.core;

import com.zhanjh.hercules.agent.port.CourseQueryPort;
import com.zhanjh.hercules.agent.port.EnrollmentPort;
import com.zhanjh.hercules.model.Course;
import com.zhanjh.hercules.model.agent.AgentContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 课程推荐智能体（阶段 D）：候选检索（走多级缓存读路径）→ 逐门冲突标注（纯规则）→
 * 生成 LLM 提示词（由编排器决定阻塞/流式调用）。
 *
 * <p>消息解析：从用户消息提取学分偏好（如「3 学分」「3.5学分」）与关键词
 * （剥离意图词后的剩余文本），二者均为可选过滤条件。
 *
 * <p>LLM 的职责边界：只基于本类给出的候选与冲突标注做挑选与理由转述，
 * 不产生候选之外的课程（提示词明确约束），候选为空时直接返回降级文案不调 LLM。
 *
 * <p>线程安全性：无实例可变状态，可并发调用。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Component
public class RecommendationAgent {

    /** 学分偏好提取：如「3 学分」「3.5学分」。 */
    private static final Pattern CREDIT_PATTERN = Pattern.compile("([0-9]+(?:\\.[0-9])?)\\s*学分");

    /** 推荐人设与输出格式约束（系统提示）。 */
    private static final String SYSTEM_PROMPT = """
            你是智慧校园选课平台的课程推荐助手。请基于给定的候选课程做推荐：
            1. 挑选 3~5 门最值得推荐的课程，逐门给出一句简洁的推荐理由；
            2. 候选中标注了「冲突」的课程不要推荐，如有必要可说明原因；
            3. 用中文输出，语气朴素，不要使用比喻和感叹号堆叠；
            4. 结尾固定加一行：如需选课，请回复：确认选课 课程编号（例如：确认选课 CS101）。""";

    /** 课程查询端口（候选检索）。 */
    private final CourseQueryPort courseQueryPort;

    /** 选课端口（本人已选，用于冲突标注）。 */
    private final EnrollmentPort enrollmentPort;

    /** 冲突校验（纯规则）。 */
    private final SchedulingAgent schedulingAgent;

    public RecommendationAgent(CourseQueryPort courseQueryPort,
                               EnrollmentPort enrollmentPort,
                               SchedulingAgent schedulingAgent) {
        this.courseQueryPort = courseQueryPort;
        this.enrollmentPort = enrollmentPort;
        this.schedulingAgent = schedulingAgent;
    }

    /**
     * 推荐准备结果：结构化候选（供 SSE meta 事件）与 LLM 提示词素材。
     *
     * @param recommendations 逐门推荐条目（含冲突标注）
     * @param systemPrompt    系统提示
     * @param userPrompt      用户侧提示（含学生已选与候选明细）
     * @param data            结构化附加数据（SSE meta 用）
     * @param fallbackText    LLM 不可用时的降级文案
     * @author zhanjh
     * @since 0.0.1
     */
    public record Prepared(List<Recommendation> recommendations,
                           String systemPrompt,
                           String userPrompt,
                           Map<String, Object> data,
                           String fallbackText) {
    }

    /**
     * 单门候选课程的推荐条目。
     *
     * @param course    课程对象
     * @param conflicted 是否与学生已选冲突（true 不推荐）
     * @param conflicts  冲突明细
     * @author zhanjh
     * @since 0.0.1
     */
    public record Recommendation(Course course, boolean conflicted, List<String> conflicts) {
    }

    /**
     * 从用户消息解析偏好并准备候选与提示词。
     *
     * @param ctx 对话上下文
     * @return 准备结果（从不为 null；候选为空时 fallbackText 给出提示）
     */
    public Prepared prepare(AgentContext ctx) {
        String message = ctx.message() == null ? "" : ctx.message();
        Matcher creditMatcher = CREDIT_PATTERN.matcher(message);
        java.math.BigDecimal creditFilter = null;
        if (creditMatcher.find()) {
            try {
                creditFilter = new java.math.BigDecimal(creditMatcher.group(1));
            } catch (NumberFormatException ignored) {
                // 学分偏好解析失败按未指定处理
            }
        }
        String keyword = stripIntentWords(message);

        List<Course> candidates = courseQueryPort.search(keyword, 20);
        if (candidates.isEmpty() && keyword != null && !keyword.isBlank()) {
            // 关键词过严（剥离意图词后仅剩零散单字）时回退全量候选，推荐链不空手而归
            candidates = courseQueryPort.search("", 20);
        }
        List<Course> mine = ctx.isStudent() ? enrollmentPort.myEnrolledCourses(ctx.studentId()) : List.of();

        List<Recommendation> recommendations = new ArrayList<>();
        for (Course candidate : candidates) {
            if (creditFilter != null && (candidate.getCredit() == null
                    || candidate.getCredit().compareTo(creditFilter) != 0)) {
                continue;
            }
            SchedulingAgent.ConflictCheck check = schedulingAgent.check(candidate, mine);
            recommendations.add(new Recommendation(candidate, !check.passed(), check.conflicts()));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("intent", "RECOMMEND");
        data.put("keyword", keyword);
        data.put("creditFilter", creditFilter);
        data.put("candidateCount", recommendations.size());
        data.put("candidates", recommendations.stream()
                .map(r -> Map.of(
                        "id", r.course().getId(),
                        "code", nullSafe(r.course().getCourseCode()),
                        "name", nullSafe(r.course().getCourseName()),
                        "credit", nullSafe(r.course().getCredit()),
                        "conflicted", r.conflicted(),
                        "conflicts", r.conflicts()))
                .toList());

        String fallbackText = recommendations.isEmpty()
                ? "没有找到匹配的课程。可以换个关键词，或直接告诉我课程名称。"
                : "推荐服务暂时不可用，以下是匹配的课程：\n" + renderCandidates(
                        recommendations.stream().filter(r -> !r.conflicted()).limit(5).toList());

        return new Prepared(recommendations, SYSTEM_PROMPT, buildUserPrompt(ctx, recommendations), data, fallbackText);
    }

    /**
     * 构建用户侧提示词：学生已选摘要 + 候选明细（最多 8 条，无冲突优先）。
     *
     * @param ctx            对话上下文
     * @param recommendations 推荐条目
     * @return 提示词文本
     */
    private String buildUserPrompt(AgentContext ctx, List<Recommendation> recommendations) {
        StringBuilder sb = new StringBuilder();
        if (ctx.isStudent()) {
            sb.append("学生已选课程：");
            List<Course> mine = enrollmentPort.myEnrolledCourses(ctx.studentId());
            if (mine.isEmpty()) {
                sb.append("暂无");
            } else {
                sb.append(mine.stream()
                        .map(c -> "《" + nullSafe(c.getCourseName()) + "》(" + nullSafe(c.getCourseCode())
                                + ", " + nullSafe(c.getCredit()) + "学分)")
                        .reduce((a, b) -> a + "、" + b).orElse(""));
            }
            sb.append("\n\n");
        }
        sb.append("候选课程（conflict=true 的不要推荐）：\n");
        recommendations.stream()
                .sorted(java.util.Comparator.comparing(Recommendation::conflicted))
                .limit(8)
                .forEach(r -> sb.append("- [").append(nullSafe(r.course().getCourseCode())).append("] ")
                        .append(nullSafe(r.course().getCourseName()))
                        .append("｜").append(nullSafe(r.course().getTeacherName()))
                        .append("｜").append(nullSafe(r.course().getCredit())).append("学分")
                        .append(r.conflicted() ? "｜冲突：" + String.join("；", r.conflicts()) : "")
                        .append("\n"));
        return sb.toString();
    }

    /**
     * 渲染候选列表为纯文本（降级文案用）。
     *
     * @param recommendations 推荐条目
     * @return 多行文本
     */
    public String renderCandidates(List<Recommendation> recommendations) {
        StringBuilder sb = new StringBuilder();
        for (Recommendation r : recommendations) {
            sb.append("- ").append(nullSafe(r.course().getCourseName()))
                    .append("（").append(nullSafe(r.course().getCourseCode()))
                    .append("，").append(nullSafe(r.course().getCredit())).append("学分）\n");
        }
        sb.append("如需选课，请回复：确认选课 课程编号（例如：确认选课 CS101）。");
        return sb.toString();
    }

    /**
     * 剥离意图词与语气词，保留文本作为检索关键词。
     *
     * @param message 用户消息
     * @return 关键词（空串表示全量）
     */
    private String stripIntentWords(String message) {
        String stripped = message
                .replaceAll("推荐一下|推荐|建议|有什么|有哪些|找一下|查一下|查询|看看|搜索|几门|哪些|给我|帮忙|帮我|一下|谢谢|适合|的课", "")
                .trim();
        return stripped.isBlank() ? "" : stripped;
    }

    /**
     * null 安全的字符串化（Map 值渲染用）。
     *
     * @param value 任意值
     * @return 字符串形式，null → ""
     */
    private String nullSafe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
