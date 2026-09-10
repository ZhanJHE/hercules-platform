package com.zhanjh.hercules.agent.port;

import com.zhanjh.hercules.model.Course;

import java.util.List;

/**
 * 课程查询端口（阶段 D）：多智能体模块对课程读能力的唯一依赖边界，
 * 由 application 模块的 AgentPortAdapter 适配到 CourseService（走多级缓存读路径）。
 *
 * <p>端口反转动机：依赖方向 common ← cache ← sync ← agent ← application 不可成环，
 * agent 不能直接依赖 application 的服务类；经端口接口 + 适配器实现解耦。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public interface CourseQueryPort {

    /**
     * 按关键词检索课程（匹配课程名/编号/教师名，LIKE 模糊）。
     *
     * @param keyword 关键词（null/空白 = 全量）
     * @param limit   返回上限（分页 size）
     * @return 候选课程列表
     */
    List<Course> search(String keyword, int limit);

    /**
     * 按主键查课程详情。
     *
     * @param id 课程主键
     * @return 课程对象；不存在时为 null
     */
    Course detail(Long id);
}
