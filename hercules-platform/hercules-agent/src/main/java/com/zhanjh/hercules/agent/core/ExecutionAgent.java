package com.zhanjh.hercules.agent.core;

import com.zhanjh.hercules.common.BusinessException;
import com.zhanjh.hercules.model.Course;
import com.zhanjh.hercules.model.agent.AgentContext;
import com.zhanjh.hercules.agent.port.CourseQueryPort;
import com.zhanjh.hercules.agent.port.EnrollmentPort;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行智能体（阶段 D）：对<b>已通过冲突校验并经用户确认</b>的选课请求执行写入。
 *
 * <p>安全语义：
 * <ul>
 *   <li>仅 STUDENT 角色可执行（studentId 取自 JWT 上下文，与直连接口同源，无伪造空间）；</li>
 *   <li>写入口是唯一的——经 {@link EnrollmentPort} 调用 EnrollmentService，防超选条件 UPDATE、
 *       重复选课唯一键、越权收敛全部复用；</li>
 *   <li>业务失败（409 容量满/重复选课、404 不存在）转为对话文案原样呈现，不包装成系统错误。</li>
 * </ul>
 *
 * <p>线程安全性：无实例可变状态，可并发调用。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Component
public class ExecutionAgent {

    /** 课程查询端口（执行后回读课程信息，组装确认文案）。 */
    private final CourseQueryPort courseQueryPort;

    /** 选课端口（唯一写入口）。 */
    private final EnrollmentPort enrollmentPort;

    public ExecutionAgent(CourseQueryPort courseQueryPort, EnrollmentPort enrollmentPort) {
        this.courseQueryPort = courseQueryPort;
        this.enrollmentPort = enrollmentPort;
    }

    /**
     * 执行结果：文本（流式/整段输出给用户）与结构化数据。
     *
     * @param success 写入是否成功（业务 4xx 也算 false，但文案面向用户）
     * @param text    对话文本
     * @param data    结构化数据（enrollmentId/courseId 等），可 null
     * @author zhanjh
     * @since 0.0.1
     */
    public record ExecOutcome(boolean success, String text, Map<String, Object> data) {
    }

    /**
     * 确认执行选课。
     *
     * @param ctx      对话上下文（studentId 来自 JWT）
     * @param courseId 待选课程主键（来自上一步冲突校验通过的请求）
     * @return 执行结果（从不为 null）
     */
    public ExecOutcome confirmEnroll(AgentContext ctx, Long courseId) {
        if (!ctx.isStudent()) {
            return new ExecOutcome(false, "仅学生角色可以执行选课操作。", null);
        }
        try {
            Long enrollmentId = enrollmentPort.enroll(ctx.studentId(), courseId);
            Course course = courseQueryPort.detail(courseId);
            String name = course == null || course.getCourseName() == null ? String.valueOf(courseId) : course.getCourseName();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("enrollmentId", enrollmentId);
            data.put("courseId", courseId);
            data.put("courseName", name);
            return new ExecOutcome(true,
                    "已为你选上《" + name + "》。选课人数已落库，列表缓存将在 10 秒内反映最新状态；课程详情已即时刷新。", data);
        } catch (BusinessException e) {
            // 预期业务失败（409 容量满/重复选课、404 不存在）：文案面向用户原样呈现
            return new ExecOutcome(false, "选课未成功：" + e.getMessage(), null);
        }
    }
}
