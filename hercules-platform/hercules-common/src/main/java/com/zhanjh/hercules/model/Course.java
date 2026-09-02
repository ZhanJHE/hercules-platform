package com.zhanjh.hercules.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 课程实体，对应起步文档 §5.1.1 的 t_course 表。
 *
 * <p>MyBatis-Plus 实体，驼峰属性自动映射下划线列（map-underscore-to-camel-case 默认开启）。
 * scheduleJson / prerequisitesJson 两个 JSON 列 MVP 阶段以 TEXT 承载，由应用层经 Jackson（JsonUtil）序列化与校验，
 * 数据库层不做 JSON 类型约束。本实体经 JsonUtil 序列化后写入详情缓存键 course:{id}（L1 Caffeine / L2 Redis），
 * 并纳入向量时钟版本链；enrolled 仅由 CourseMapper 的原子条件更新增减，保证 0 &lt;= enrolled &lt;= capacity。
 *
 * <p>线程安全性：普通可变 POJO，仅在单请求作用域内使用，不做并发共享。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@TableName("t_course")
public class Course {

    /** 主键，数据库自增（t_course.id，IdType.AUTO）。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 课程编码，业务上的课程唯一标识，参与列表关键词模糊匹配（t_course.course_code）。 */
    private String courseCode;

    /** 课程名称，参与列表关键词模糊匹配（t_course.course_name）。 */
    private String courseName;

    /** 任课教师姓名，参与列表关键词模糊匹配（t_course.teacher_name）。 */
    private String teacherName;

    /** 学分，用 BigDecimal 承载小数学分（如 2.5）（t_course.credit）。 */
    private BigDecimal credit;

    /** 容量上限，正整数；enrolled 达到该值时 increaseEnrolled 的条件更新不再生效（t_course.capacity）。 */
    private Integer capacity;

    /** 已选人数，取值范围 [0, capacity]，仅经原子条件更新 +1/-1，防止并发超选/负数（t_course.enrolled）。 */
    private Integer enrolled;

    /**
     * 上课时间 JSON（t_course.schedule_json，TEXT）：如 {"day":1,"sections":[1,2]}，
     * day 取 1-7 对应周一至周日，sections 为节次编号列表；应用层经 Jackson 校验。
     */
    private String scheduleJson;

    /**
     * 先修课程 id 数组 JSON（t_course.prerequisites_json，TEXT）：如 [1,2]；应用层经 Jackson 校验。
     */
    private String prerequisitesJson;

    /** 教学大纲文件 URL，可为 null（t_course.syllabus_url）。 */
    private String syllabusUrl;

    /**
     * 最后更新时间（t_course.update_time）；increaseEnrolled / decreaseEnrolled 会将其刷新为
     * 数据库 CURRENT_TIMESTAMP(3)（毫秒精度），可用于观察 DB 侧最近变更时间。
     */
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public void setCourseCode(String courseCode) {
        this.courseCode = courseCode;
    }

    public String getCourseName() {
        return courseName;
    }

    public void setCourseName(String courseName) {
        this.courseName = courseName;
    }

    public String getTeacherName() {
        return teacherName;
    }

    public void setTeacherName(String teacherName) {
        this.teacherName = teacherName;
    }

    public BigDecimal getCredit() {
        return credit;
    }

    public void setCredit(BigDecimal credit) {
        this.credit = credit;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public Integer getEnrolled() {
        return enrolled;
    }

    public void setEnrolled(Integer enrolled) {
        this.enrolled = enrolled;
    }

    public String getScheduleJson() {
        return scheduleJson;
    }

    public void setScheduleJson(String scheduleJson) {
        this.scheduleJson = scheduleJson;
    }

    public String getPrerequisitesJson() {
        return prerequisitesJson;
    }

    public void setPrerequisitesJson(String prerequisitesJson) {
        this.prerequisitesJson = prerequisitesJson;
    }

    public String getSyllabusUrl() {
        return syllabusUrl;
    }

    public void setSyllabusUrl(String syllabusUrl) {
        this.syllabusUrl = syllabusUrl;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
