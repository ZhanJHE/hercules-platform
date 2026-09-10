package com.zhanjh.hercules.agent.port;

import com.zhanjh.hercules.model.Course;

import java.util.List;

/**
 * 选课端口（阶段 D）：多智能体模块对选课写能力与本人选课记录的依赖边界，
 * 由 application 模块的 AgentPortAdapter 适配到 EnrollmentService（防超选/唯一键/越权收敛全保留）。
 *
 * <p>调用契约：enroll 内部以传入 studentId 构建认证主体（智能体链不含伪造空间——
 * studentId 来自 JWT 令牌，与直连接口同源）；业务冲突（容量满/重复选课）以
 * BusinessException（code=409/404）向上传播，由执行智能体转为对话文案。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public interface EnrollmentPort {

    /**
     * 为学生选课（等价于 POST /api/v1/enrollment 的服务端语义）。
     *
     * @param studentId 学生业务号（取自 JWT，非前端传参）
     * @param courseId  课程主键
     * @return 选课记录主键
     */
    Long enroll(Long studentId, Long courseId);

    /**
     * 查询学生当前有效选课（status=1）对应的课程列表（冲突校验的数据源）。
     *
     * @param studentId 学生业务号
     * @return 已选课程列表（含上课时间/学分/先修字段）
     */
    List<Course> myEnrolledCourses(Long studentId);
}
