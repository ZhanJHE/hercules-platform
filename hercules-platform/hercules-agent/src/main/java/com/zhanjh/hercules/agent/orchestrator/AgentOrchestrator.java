package com.zhanjh.hercules.agent.orchestrator;

import com.zhanjh.hercules.agent.config.AgentProperties;
import com.zhanjh.hercules.agent.core.ExecutionAgent;
import com.zhanjh.hercules.agent.core.LlmPort;
import com.zhanjh.hercules.agent.core.RecommendationAgent;
import com.zhanjh.hercules.agent.core.RoutingAgent;
import com.zhanjh.hercules.agent.core.SchedulingAgent;
import com.zhanjh.hercules.agent.port.CourseQueryPort;
import com.zhanjh.hercules.agent.port.EnrollmentPort;
import com.zhanjh.hercules.agent.support.AgentTraceWriter;
import com.zhanjh.hercules.model.Course;
import com.zhanjh.hercules.model.agent.AgentContext;
import com.zhanjh.hercules.model.agent.AgentType;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 编排器（阶段 D）：对话请求的总入口——意图路由 → 智能体链执行 → 输出流与结构化数据。
 *
 * <p>三条链路：
 * <ol>
 *   <li><b>RECOMMEND / UNKNOWN</b>：候选检索 + 冲突标注 → LLM 流式生成推荐（LLM 失败降级为
 *       纯文本候选列表）；</li>
 *   <li><b>ENROLL</b>：解析课程线索 → 冲突校验（纯规则）→ 通过则进入待确认状态（确认制，
 *       写操作不由 LLM 自主触发）；</li>
 *   <li><b>CONFIRM</b>：取出待确认请求 → 执行智能体调选课端口 → 结果文案。</li>
 * </ol>
 *
 * <p>输出形态：{@link Orchestration} = token 流（SSE 逐片下发）+ 元数据供应器
 * （流完成后作为 meta 事件发送，携带候选/冲突等结构化信息）。纯文本路径以单元素流统一形态。
 *
 * <p>线程安全性：无实例可变状态；会话态在 {@link ConversationHistory}（ConcurrentHashMap）。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@org.springframework.stereotype.Component
public class AgentOrchestrator {

    /** 日志记录器（降级路径观测）。 */
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AgentOrchestrator.class);

    /** 意图路由智能体。 */
    private final RoutingAgent routingAgent;
    /** 课程推荐智能体。 */
    private final RecommendationAgent recommendationAgent;
    /** 排课冲突智能体（纯规则）。 */
    private final SchedulingAgent schedulingAgent;
    /** 执行智能体。 */
    private final ExecutionAgent executionAgent;
    /** LLM 端口（推荐解释流式生成、意图兜底分类）。 */
    private final LlmPort llm;
    /** 课程查询端口（ENROLL 分支按编码/名称定位课程）。 */
    private final CourseQueryPort courseQueryPort;
    /** 选课端口（冲突校验时取本人已选）。 */
    private final EnrollmentPort enrollmentPort;
    /** 会话历史与待确认状态。 */
    private final ConversationHistory history;
    /** trace 写入器。 */
    private final AgentTraceWriter traceWriter;
    /** 编排配置。 */
    private final AgentProperties props;

    public AgentOrchestrator(RoutingAgent routingAgent,
                             RecommendationAgent recommendationAgent,
                             SchedulingAgent schedulingAgent,
                             ExecutionAgent executionAgent,
                             LlmPort llm,
                             CourseQueryPort courseQueryPort,
                             EnrollmentPort enrollmentPort,
                             ConversationHistory history,
                             AgentTraceWriter traceWriter,
                             AgentProperties props) {
        this.routingAgent = routingAgent;
        this.recommendationAgent = recommendationAgent;
        this.schedulingAgent = schedulingAgent;
        this.executionAgent = executionAgent;
        this.llm = llm;
        this.courseQueryPort = courseQueryPort;
        this.enrollmentPort = enrollmentPort;
        this.history = history;
        this.traceWriter = traceWriter;
        this.props = props;
    }

    /**
     * 编排输出：token 流 + 元数据供应器。
     *
     * @param tokens    输出分片流（SSE token 事件）
     * @param metadata  流完成后发送的结构化数据（SSE meta 事件）
     * @author zhanjh
     * @since 0.0.1
     */
    public record Orchestration(Flux<String> tokens, Supplier<Map<String, Object>> metadata) {
    }

    /**
     * 处理一次对话请求。
     *
     * @param ctx 对话上下文（含本轮消息与历史）
     * @return 编排输出（从不为 null）
     */
    public Orchestration handle(AgentContext ctx) {
        history.addUserTurn(ctx.sessionId(), ctx.message());
        Intent intent = routingAgent.classify(ctx.message());
        return switch (intent) {
            case CONFIRM -> confirm(ctx);
            case ENROLL -> enroll(ctx, intent);
            default -> recommend(ctx, intent);
        };
    }

    /**
     * 确认执行链：取出待确认请求 → 执行智能体写入选课。
     *
     * @param ctx 对话上下文
     * @return 编排输出（整段文本）
     */
    private Orchestration confirm(AgentContext ctx) {
        Long courseId = history.takePendingEnrollment(ctx.sessionId());
        if (courseId == null) {
            return text("当前没有待确认的选课请求。可以先说「帮我选 XX」，通过冲突校验后再回复确认。", null);
        }
        ExecutionAgent.ExecOutcome outcome = executionAgent.confirmEnroll(ctx, courseId);
        traceWriter.write(ctx, AgentType.EXECUTION, ctx.message(), outcome.text(), outcome.success());
        return text(outcome.text(), outcome.data());
    }

    /**
     * 选课请求链：解析课程线索 → 冲突校验 → 通过则进入待确认状态。
     *
     * @param ctx    对话上下文
     * @param intent 意图（ENROLL，仅用于 trace 归类）
     * @return 编排输出（整段文本：确认请求或冲突明细）
     */
    private Orchestration enroll(AgentContext ctx, Intent intent) {
        RoutingAgent.CourseRef ref = routingAgent.extractCourseRef(ctx.message());
        if (ref == null) {
            return text("请告诉我要选的课程名称或编号，例如：帮我选 CS101，或帮我选《数据结构》。", null);
        }
        List<Course> matches = resolve(ref);
        if (matches.isEmpty()) {
            return text("没有找到对应的课程。可以用「推荐课程」浏览当前可选列表。", null);
        }
        if (matches.size() > 1) {
            StringBuilder sb = new StringBuilder("找到多门匹配的课程，请指定课程编号：\n");
            matches.stream().limit(5).forEach(c -> sb.append("- [").append(c.getCourseCode()).append("] ")
                    .append(c.getCourseName()).append("（").append(c.getCredit()).append("学分）\n"));
            return text(sb.toString(), null);
        }
        Course course = matches.get(0);
        List<Course> mine = ctx.isStudent() ? enrollmentPort.myEnrolledCourses(ctx.studentId()) : List.of();
        SchedulingAgent.ConflictCheck check = schedulingAgent.check(course, mine);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("intent", "ENROLL");
        data.put("courseId", course.getId());
        data.put("courseName", course.getCourseName());
        data.put("passed", check.passed());
        data.put("conflicts", check.conflicts());
        traceWriter.write(ctx, AgentType.SCHEDULING, ctx.message(),
                check.passed() ? "冲突校验通过" : String.join("；", check.conflicts()), true);
        if (!check.passed()) {
            StringBuilder sb = new StringBuilder("《").append(course.getCourseName()).append("》无法选课：\n");
            check.conflicts().forEach(c -> sb.append("- ").append(c).append("\n"));
            return text(sb.toString(), data);
        }
        history.setPendingEnrollment(ctx.sessionId(), course.getId());
        return text("《" + course.getCourseName() + "》（" + course.getCourseCode()
                + "，" + course.getCredit() + "学分，教师：" + course.getTeacherName()
                + "）通过冲突校验，选课成功后总学分约 " + check.totalCreditsAfter() + "。\n"
                + "回复「确认」即可为你选课；回复其他内容则取消。", data);
    }

    /**
     * 推荐/降级链：候选检索 + 冲突标注 → LLM 流式生成（失败降级为纯文本候选）。
     *
     * @param ctx    对话上下文
     * @param intent 意图（RECOMMEND 或 UNKNOWN）
     * @return 编排输出（token 流）
     */
    private Orchestration recommend(AgentContext ctx, Intent intent) {
        RecommendationAgent.Prepared prepared = recommendationAgent.prepare(ctx);
        if (prepared.recommendations().isEmpty()) {
            return text(prepared.fallbackText(), prepared.data());
        }
        Flux<String> tokens = props.isEnabled()
                ? llmStreamWithFallback(ctx, prepared)
                : Flux.just(prepared.fallbackText());
        return new Orchestration(tokens, () -> prepared.data());
    }

    /**
     * LLM 流式生成 + 降级：任何错误（超时/断网/解析）转为其结构化候选的纯文本，对话不中断。
     *
     * @param ctx      对话上下文
     * @param prepared 推荐准备结果
     * @return token 流（永不 error）
     */
    private Flux<String> llmStreamWithFallback(AgentContext ctx, RecommendationAgent.Prepared prepared) {
        return llm.stream(prepared.systemPrompt(), prepared.userPrompt())
                .onErrorResume(e -> {
                    log.warn("[hercules-agent] llm stream failed, fallback to plain list: {}", e.getMessage());
                    return Flux.just(prepared.fallbackText());
                })
                .doOnComplete(() -> traceWriter.write(ctx, AgentType.RECOMMENDATION,
                        ctx.message(), "(流式推荐，候选 " + prepared.recommendations().size() + " 条)", true));
    }

    /**
     * 按线索定位课程：主键 → 精确编码 → 精确名称（多个精确名称匹配视为歧义，返回全部）。
     *
     * @param ref 课程线索
     * @return 匹配课程列表（空 = 未找到）
     */
    private List<Course> resolve(RoutingAgent.CourseRef ref) {
        if (ref.id() != null) {
            Course course = courseQueryPort.detail(ref.id());
            return course == null ? List.of() : List.of(course);
        }
        String term = ref.code() != null ? ref.code() : ref.name();
        List<Course> candidates = courseQueryPort.search(term, 10);
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Course> exact = candidates.stream()
                .filter(c -> (ref.code() != null && ref.code().equalsIgnoreCase(c.getCourseCode()))
                        || (ref.name() != null && ref.name().equals(c.getCourseName())))
                .toList();
        if (!exact.isEmpty()) {
            return exact;
        }
        // 无精确匹配时原样返回候选：唯一则直接进入校验，多个则由用户挑选（enroll 分支处理）
        return candidates;
    }

    /**
     * 构建整段文本形态的编排输出。
     *
     * @param text 文本
     * @param data 元数据，可 null
     * @return 编排输出
     */
    private Orchestration text(String text, Map<String, Object> data) {
        return new Orchestration(Flux.just(text), () -> data == null ? Map.of() : data);
    }
}
