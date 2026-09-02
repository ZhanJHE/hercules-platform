package com.zhanjh.hercules.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhanjh.hercules.model.Enrollment;

/**
 * 学生选课记录表 Mapper：仅继承 MyBatis-Plus BaseMapper，无自定义 SQL，CRUD 全部来自通用接口。
 *
 * <p>实际用到的通用能力（见 EnrollmentService）：
 * <ul>
 *   <li>insert —— 写入选课记录，status/create_time/update_time 由应用层赋值；</li>
 *   <li>selectOne + QueryWrapper —— 按 student_id + course_id + status=1 查有效选课，
 *       配合 orderByDesc("id").last("LIMIT 1") 取最新一条；</li>
 *   <li>updateById —— 退课时更新 status 与 update_time。</li>
 * </ul>
 *
 * <p>MyBatis 动态代理生成实现，单例无状态，线程安全。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public interface EnrollmentMapper extends BaseMapper<Enrollment> {
}
