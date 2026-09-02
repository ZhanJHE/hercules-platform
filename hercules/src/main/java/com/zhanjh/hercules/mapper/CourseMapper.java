package com.zhanjh.hercules.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhanjh.hercules.model.Course;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 课程表 Mapper：继承 MyBatis-Plus BaseMapper 获得通用 CRUD（selectById、selectPage、insert、updateById 等），
 * 并补充选课人数的原子条件更新，作为防超选/防负数的数据库侧硬保障（FR-HC-01 写路径）。
 *
 * <p>两条 UPDATE 均为单行行锁下的条件更新，并发选课/退课在数据库层天然串行化，应用层无需额外加锁；
 * 两者都同步把 update_time 刷新为 CURRENT_TIMESTAMP(3)（毫秒精度）。
 * MyBatis 动态代理生成实现，单例无状态，线程安全。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public interface CourseMapper extends BaseMapper<Course> {

    /**
     * 原子占坑：仅当仍有余量（enrolled &lt; capacity）时把 enrolled +1，保证并发选课不超容量。
     * 返回行数由调用方（EnrollmentService.enroll）判定：1-占坑成功；0-容量已满，映射为 409 冲突
     * （课程不存在的情况在调用前已被 selectById 排除）。
     *
     * @param id 课程主键，非 null
     * @return 受影响行数：1 表示占坑成功，0 表示容量已满或课程不存在
     */
    @Update("UPDATE t_course SET enrolled = enrolled + 1, update_time = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{id} AND enrolled < capacity")
    int increaseEnrolled(@Param("id") Long id);

    /**
     * 原子退坑：仅当已选人数大于 0（enrolled &gt; 0）时把 enrolled -1，与 increaseEnrolled 对称，防止退成负数。
     * 返回行数由调用方（EnrollmentService.withdraw）判定：1-退坑成功；0-已选人数已为 0，映射为 409 冲突。
     *
     * @param id 课程主键，非 null
     * @return 受影响行数：1 表示退坑成功，0 表示已选人数为 0 或课程不存在
     */
    @Update("UPDATE t_course SET enrolled = enrolled - 1, update_time = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{id} AND enrolled > 0")
    int decreaseEnrolled(@Param("id") Long id);
}
