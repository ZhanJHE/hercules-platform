package com.zhanjh.hercules.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 学生选课记录实体，对应起步文档 §5.1.2 的 t_enrollment 表。
 *
 * <p>状态机：0-预选 → 1-已选 → 2-退选（对应本类 STATUS_* 常量）。MVP 选课直接插入 STATUS_ENROLLED，
 * 预选态为后续「预选-转正」流程预留；退课不物理删除，把 status 置为 STATUS_WITHDRAWN 保留历史，
 * 重新选课插入新记录。选课/退课事务提交后由 EnrollmentService 发布 course:{id} 的版本化值刷新缓存，
 * 本表自身不参与缓存版本链。
 *
 * <p>线程安全性：普通可变 POJO，仅在单请求作用域内使用。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@TableName("t_enrollment")
public class Enrollment {

    /** 状态取值 0：预选/候选，MVP 写路径暂不产生该状态，为预选-转正流程预留。 */
    public static final int STATUS_PRESELECT = 0;

    /** 状态取值 1：已选（占坑生效），选课成功后的状态。 */
    public static final int STATUS_ENROLLED = 1;

    /** 状态取值 2：退选（软删除标记），保留记录历史，不参与有效选课判定。 */
    public static final int STATUS_WITHDRAWN = 2;

    /** 主键，数据库自增（t_enrollment.id，IdType.AUTO）。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 学生 id（t_enrollment.student_id）。 */
    private Long studentId;

    /** 课程 id，指向 t_course.id（t_enrollment.course_id）。 */
    private Long courseId;

    /** 记录状态：0-预选 1-已选 2-退选，见 STATUS_* 常量（t_enrollment.status）。 */
    private Integer status;

    /** 创建时间，由应用层以 LocalDateTime.now() 写入（t_enrollment.create_time）。 */
    private LocalDateTime createTime;

    /** 最后更新时间，退课置状态时刷新（t_enrollment.update_time）。 */
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getStudentId() {
        return studentId;
    }

    public void setStudentId(Long studentId) {
        this.studentId = studentId;
    }

    public Long getCourseId() {
        return courseId;
    }

    public void setCourseId(Long courseId) {
        this.courseId = courseId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
