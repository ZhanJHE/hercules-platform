package com.zhanjh.hercules.adapter;

import com.zhanjh.hercules.agent.port.CourseQueryPort;
import com.zhanjh.hercules.agent.port.EnrollmentPort;
import com.zhanjh.hercules.auth.JwtUtil;
import com.zhanjh.hercules.model.Course;
import com.zhanjh.hercules.model.Enrollment;
import com.zhanjh.hercules.service.CourseService;
import com.zhanjh.hercules.service.EnrollmentService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 智能体业务端口适配器（阶段 D）：把 agent 模块声明的 {@link CourseQueryPort}/
 * {@link EnrollmentPort} 适配到 application 层的 CourseService/EnrollmentService。
 *
 * <p>依赖方向：application → agent（单向），agent 不感知 application——业务语义
 * （多级缓存读、防超选、唯一键、越权收敛）全部经适配复用，智能体链零旁路。
 *
 * <p>认证主体重建：EnrollmentService.enroll 以 AuthClaims 校验角色与 studentId；
 * 智能体链的 studentId 来自 JWT 上下文（ChatController 构建 AgentContext），此处
 * 重建 claims 的 username/jti/exp 不参与选课语义，以占位值填充。
 *
 * <p>线程安全性：依赖均为无状态 Bean，可并发调用。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Component
public class AgentPortAdapter implements CourseQueryPort, EnrollmentPort {

    /** 课程查询服务（多级缓存读路径）。 */
    private final CourseService courseService;

    /** 选课服务（防超选写路径 + 本人记录查询）。 */
    private final EnrollmentService enrollmentService;

    public AgentPortAdapter(CourseService courseService, EnrollmentService enrollmentService) {
        this.courseService = courseService;
        this.enrollmentService = enrollmentService;
    }

    /**
     * 关键词检索课程（经多级缓存列表键）。
     *
     * @param keyword 关键词（空 = 全量首页）
     * @param limit   上限
     * @return 候选课程列表
     */
    @Override
    public List<Course> search(String keyword, int limit) {
        return courseService.list(1, limit, keyword).records();
    }

    /**
     * 课程详情（经多级缓存详情键，纳入版本链）。
     *
     * @param id 课程主键
     * @return 课程对象或 null
     */
    @Override
    public Course detail(Long id) {
        return courseService.getCourse(id);
    }

    /**
     * 执行选课（防超选 + 唯一键 + 越权收敛全保留）。
     *
     * @param studentId 学生业务号（来自 JWT 上下文）
     * @param courseId  课程主键
     * @return 选课记录主键
     */
    @Override
    public Long enroll(Long studentId, Long courseId) {
        // 重建认证主体：选课语义只使用 role/studentId；username/jti/exp 为占位
        JwtUtil.AuthClaims claims = new JwtUtil.AuthClaims(0L, "agent-chain", "STUDENT", studentId, "agent-port", 0L);
        return enrollmentService.enroll(claims, courseId).getId();
    }

    /**
     * 本人当前有效选课对应的课程列表（冲突校验数据源）。
     *
     * @param studentId 学生业务号
     * @return 已选课程列表
     */
    @Override
    public List<Course> myEnrolledCourses(Long studentId) {
        JwtUtil.AuthClaims claims = new JwtUtil.AuthClaims(0L, "agent-chain", "STUDENT", studentId, "agent-port", 0L);
        return enrollmentService.mine(claims).stream()
                .filter(e -> e.getStatus() != null && e.getStatus() == Enrollment.STATUS_ENROLLED)
                .map(e -> courseService.getCourse(e.getCourseId()))
                .filter(Objects::nonNull)
                .toList();
    }
}
