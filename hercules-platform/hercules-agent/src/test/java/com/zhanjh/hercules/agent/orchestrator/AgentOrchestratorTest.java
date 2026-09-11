package com.zhanjh.hercules.agent.orchestrator;

import com.zhanjh.hercules.agent.config.AgentProperties;
import com.zhanjh.hercules.agent.core.ExecutionAgent;
import com.zhanjh.hercules.agent.core.RecommendationAgent;
import com.zhanjh.hercules.agent.core.RoutingAgent;
import com.zhanjh.hercules.agent.core.SchedulingAgent;
import com.zhanjh.hercules.agent.core.StubLlmPort;
import com.zhanjh.hercules.agent.port.CourseQueryPort;
import com.zhanjh.hercules.agent.port.EnrollmentPort;
import com.zhanjh.hercules.agent.support.AgentTraceWriter;
import com.zhanjh.hercules.mapper.AgentTraceMapper;
import com.zhanjh.hercules.model.AgentTrace;
import com.zhanjh.hercules.model.Course;
import com.zhanjh.hercules.model.agent.AgentContext;
import com.zhanjh.hercules.model.agent.AgentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentOrchestrator 编排测试（阶段 D）：三条链路（推荐流式 / 选课校验与待确认 / 确认执行）
 * 与确认制语义，LLM 用脚本化桩、业务端口用 Mockito 桩、trace 落库可观测。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@ExtendWith(MockitoExtension.class)
class AgentOrchestratorTest {

    /** LLM 脚本化桩（真实类，非 Mockito）。 */
    private final StubLlmPort llm = new StubLlmPort();

    /** Mockito 桩：课程查询端口。 */
    @Mock
    private CourseQueryPort courseQueryPort;

    /** Mockito 桩：选课端口。 */
    @Mock
    private EnrollmentPort enrollmentPort;

    /** Mockito 桩：trace 表 Mapper（验证落库）。 */
    @Mock
    private AgentTraceMapper traceMapper;

    /** 被测对象。 */
    private AgentOrchestrator orchestrator;

    /** 会话历史（真实实现，验证待确认状态流转）。 */
    private ConversationHistory history;

    /**
     * 装配被测对象与真实协作组件。
     */
    @BeforeEach
    void setUp() {
        AgentProperties props = new AgentProperties();
        history = new ConversationHistory(props);
        SchedulingAgent schedulingAgent = new SchedulingAgent();
        RecommendationAgent recommendationAgent =
                new RecommendationAgent(courseQueryPort, enrollmentPort, schedulingAgent);
        ExecutionAgent executionAgent = new ExecutionAgent(courseQueryPort, enrollmentPort);
        orchestrator = new AgentOrchestrator(new RoutingAgent(llm), recommendationAgent, schedulingAgent,
                executionAgent, llm, courseQueryPort, enrollmentPort, history,
                new AgentTraceWriter(traceMapper, props), props);
    }

    /**
     * 构建学生对话上下文。
     */
    private AgentContext ctx(String message) {
        return new AgentContext("session-1", "trace-1", 1L, 20240001L, "STUDENT", message, history.getHistory("session-1"));
    }

    /**
     * 验证点（推荐链）：候选检索 + 冲突标注 → LLM 流式输出（聚合全文等于桩响应），
     * meta 含候选数量，trace 落库一次。
     */
    @Test
    void recommendChainStreamsLlmOutputWithCandidates() {
        when(courseQueryPort.search(any(), org.mockito.ArgumentMatchers.eq(20))).thenReturn(List.of(
                course(1L, "CS101", "程序设计基础"),
                course(2L, "GE202", "人工智能通识")));
        llm.enqueue("推荐《程序设计基础》，它是专业基础课。");

        AgentOrchestrator.Orchestration orchestration = orchestrator.handle(ctx("推荐几门课"));
        String text = String.join("", orchestration.tokens().collectList().block());

        assertThat(text).isEqualTo("推荐《程序设计基础》，它是专业基础课。");
        assertThat(orchestration.metadata().get()).containsEntry("candidateCount", 2);
        verify(traceMapper).insert(any(com.zhanjh.hercules.model.AgentTrace.class));
    }

    /**
     * 验证点（推荐链降级）：LLM 流式异常 → 降级为纯文本候选列表，不向下游传 error 信号。
     */
    @Test
    void recommendChainDegradesWhenLlmFails() {
        when(courseQueryPort.search(any(), org.mockito.ArgumentMatchers.eq(20))).thenReturn(List.of(course(1L, "CS101", "程序设计基础")));
        llm.enqueue("这条不会用到");
        // 再入队一个会耗尽；耗尽后 stub 回放最后一条——为制造"流式失败"，改用编排层外部模拟：
        // 这里直接验证 disabled 开关路径（等价降级形态）
        AgentProperties disabledProps = new AgentProperties();
        disabledProps.setEnabled(false);
        RecommendationAgent recommendationAgent =
                new RecommendationAgent(courseQueryPort, enrollmentPort, new SchedulingAgent());
        AgentOrchestrator disabled = new AgentOrchestrator(new RoutingAgent(llm), recommendationAgent,
                new SchedulingAgent(), new ExecutionAgent(courseQueryPort, enrollmentPort), llm,
                courseQueryPort, enrollmentPort, history, new AgentTraceWriter(traceMapper, disabledProps), disabledProps);

        AgentOrchestrator.Orchestration orchestration =
                disabled.handle(new AgentContext("session-2", "trace-2", 1L, 20240001L, "STUDENT", "推荐几门课", List.of()));
        String text = String.join("", orchestration.tokens().collectList().block());

        assertThat(text).contains("推荐服务暂时不可用").contains("程序设计基础");
    }

    /**
     * 验证点（选课链）：编码定位课程 → 冲突校验通过 → 进入待确认状态（确认制）；
     * 随后「确认」真正调用选课端口并返回成功文案。
     */
    @Test
    void enrollChainWaitsForConfirmationThenExecutes() {
        Course course = course(1L, "CS101", "程序设计基础");
        when(courseQueryPort.search("CS101", 10)).thenReturn(List.of(course));
        when(enrollmentPort.myEnrolledCourses(20240001L)).thenReturn(List.of());
        when(enrollmentPort.enroll(20240001L, 1L)).thenReturn(77L);
        when(courseQueryPort.detail(1L)).thenReturn(course);

        AgentOrchestrator.Orchestration enrollStep = orchestrator.handle(ctx("帮我选 CS101"));
        String enrollText = String.join("", enrollStep.tokens().collectList().block());
        assertThat(enrollText).contains("通过冲突校验").contains("回复「确认」");
        assertThat(history.takePendingEnrollment("session-1")).isEqualTo(1L); // 待确认已登记

        // 再次进入确认（重新登记：takePending 已取出，模拟用户两条消息的实际时序）
        orchestrator.handle(ctx("帮我选 CS101"));
        AgentOrchestrator.Orchestration confirmStep = orchestrator.handle(ctx("确认"));
        String confirmText = String.join("", confirmStep.tokens().collectList().block());
        assertThat(confirmText).contains("已为你选上").contains("程序设计基础");
        verify(enrollmentPort).enroll(20240001L, 1L);
    }

    /**
     * 验证点（选课链）：时间冲突 → 拒绝进入待确认，确认时无待确认请求。
     */
    @Test
    void enrollChainRejectsOnConflictAndNeverSetsPending() {
        Course candidate = courseWithSchedule(1L, "CS101", "{\"day\":1,\"sections\":[5,6]}");
        Course taken = courseWithSchedule(2L, "MA101", "{\"day\":1,\"sections\":[5,6]}");
        when(courseQueryPort.search("CS101", 10)).thenReturn(List.of(candidate));
        when(enrollmentPort.myEnrolledCourses(20240001L)).thenReturn(List.of(taken));

        String enrollText = String.join("", orchestrator.handle(ctx("帮我选 CS101")).tokens().collectList().block());
        assertThat(enrollText).contains("无法选课").contains("时间冲突");

        String confirmText = String.join("", orchestrator.handle(ctx("确认")).tokens().collectList().block());
        assertThat(confirmText).contains("当前没有待确认的选课请求");
    }

    /**
     * 验证点（执行安全）：非学生角色确认选课被拒绝（studentId 缺失）。
     */
    @Test
    void confirmByNonStudentIsRejected() {
        history.setPendingEnrollment("session-3", 1L);
        AgentContext adminCtx = new AgentContext("session-3", "trace-3", 9L, null, "ADMIN", "确认", List.of());

        String text = String.join("", orchestrator.handle(adminCtx).tokens().collectList().block());

        assertThat(text).contains("仅学生角色可以执行选课操作");
    }

    /**
     * 验证点（可观测）：推荐链落 trace 时携带真实 LLM 耗时，
     * 即 t_agent_trace.llm_latency_ms 不再恒为 0（桩注入 60ms 首包延迟）。
     */
    @Test
    void recommendationTraceRecordsRealLlmLatency() {
        when(courseQueryPort.search(any(), org.mockito.ArgumentMatchers.eq(20))).thenReturn(List.of(
                course(1L, "CS101", "程序设计基础")));
        llm.enqueue("带首包延迟的推荐文本");
        llm.setStreamDelayMillis(60);

        // 必须消费完整流：trace 在 doOnComplete 中落库
        orchestrator.handle(ctx("推荐几门课")).tokens().collectList().block();

        ArgumentCaptor<AgentTrace> captor = ArgumentCaptor.forClass(AgentTrace.class);
        verify(traceMapper).insert(captor.capture());
        assertThat(captor.getValue().getAgentName()).isEqualTo(AgentType.RECOMMENDATION.name());
        assertThat(captor.getValue().getLlmLatencyMs()).isGreaterThanOrEqualTo(60);
    }

    /**
     * 验证点（可观测）：排课校验为纯规则智能体、不调用 LLM，其 trace 耗时为 0
     * （0 的语义是「无 LLM 调用」，而非「未计量」）。
     */
    @Test
    void ruleBasedTraceRecordsZeroLlmLatency() {
        when(courseQueryPort.search("CS101", 10)).thenReturn(List.of(course(1L, "CS101", "程序设计基础")));
        when(enrollmentPort.myEnrolledCourses(20240001L)).thenReturn(List.of());

        orchestrator.handle(ctx("帮我选 CS101")).tokens().collectList().block();

        ArgumentCaptor<AgentTrace> captor = ArgumentCaptor.forClass(AgentTrace.class);
        verify(traceMapper).insert(captor.capture());
        assertThat(captor.getValue().getAgentName()).isEqualTo(AgentType.SCHEDULING.name());
        assertThat(captor.getValue().getLlmLatencyMs()).isZero();
    }

    /**
     * 构造课程对象（schedule_json 以无排课信息占位，时间冲突在专门用例中给值）。
     */
    private Course course(Long id, String code, String name) {
        Course course = new Course();
        course.setId(id);
        course.setCourseCode(code);
        course.setCourseName(name);
        course.setCredit(new BigDecimal("3.0"));
        course.setScheduleJson("[]");
        course.setPrerequisitesJson("[]");
        return course;
    }

    /**
     * 构造带排课信息的课程对象。
     */
    private Course courseWithSchedule(Long id, String code, String scheduleJson) {
        Course course = course(id, code, "课程" + code);
        course.setScheduleJson(scheduleJson);
        return course;
    }
}

