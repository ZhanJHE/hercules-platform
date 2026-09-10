package com.zhanjh.hercules.sync.convert;

import com.zhanjh.hercules.model.Course;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * t_course 行数据映射器（阶段 B）：把 canal flatMessage 携带的「列名 → 字符串值」行数据
 * 还原为 {@link Course} 领域对象，保证与进程内发布路径（应用侧直接序列化 Course）产出
 * 的值 JSON 字段结构一致——这是字段级 LWW 合并正确裁决的前提。
 *
 * <p>还原规则：
 * <ul>
 *   <li>canal flatMessage 的行数据一律为字符串：数值列 parseLong/parseInt/BigDecimal 还原，
 *       解析失败该字段置 null（JsonUtil 以 NON_NULL 序列化，null 字段不出现在值 JSON 中，
 *       不参与合并，不会覆盖旧值的对应字段）；</li>
 *   <li>时间列 DATETIME(3) 按「含毫秒 / 不含毫秒 / ISO」三种格式依次尝试；</li>
 *   <li>主键 id 缺失或非法时返回 null，由调用方记 warn 并跳过该行（无法定位缓存键）。</li>
 * </ul>
 *
 * <p>线程安全性：仅静态纯函数，无状态，可并发调用。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public final class CourseRowMapper {

    /** 兼容 canal 输出的 DATETIME(3) 字符串格式（按序尝试，含毫秒优先）。 */
    private static final DateTimeFormatter[] DATETIME_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    };

    /** 工具类，禁止实例化。 */
    private CourseRowMapper() {
    }

    /**
     * 把 canal flatMessage 的单行数据还原为课程对象。
     *
     * @param row 列名 → 值字符串（update 事件的 after 镜像 / insert 的整行），不应为 null
     * @return 课程对象；id 列缺失或非法时返回 null（调用方跳过该行）
     */
    public static Course map(Map<String, String> row) {
        Course course = new Course();
        course.setId(parseLong(row.get("id")));
        if (course.getId() == null) {
            return null;
        }
        course.setCourseCode(row.get("course_code"));
        course.setCourseName(row.get("course_name"));
        course.setTeacherName(row.get("teacher_name"));
        course.setCredit(parseDecimal(row.get("credit")));
        course.setCapacity(parseInteger(row.get("capacity")));
        course.setEnrolled(parseInteger(row.get("enrolled")));
        course.setScheduleJson(row.get("schedule_json"));
        course.setPrerequisitesJson(row.get("prerequisites_json"));
        course.setSyllabusUrl(row.get("syllabus_url"));
        course.setUpdateTime(parseDateTime(row.get("update_time")));
        return course;
    }

    /**
     * 宽松 Long 解析：null/空白/非法均返回 null（NON_NULL 序列化下不进值 JSON）。
     *
     * @param value 原始字符串，可 null
     * @return Long 值或 null
     */
    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 宽松 Integer 解析：null/空白/非法均返回 null。
     *
     * @param value 原始字符串，可 null
     * @return Integer 值或 null
     */
    private static Integer parseInteger(String value) {
        Long parsed = parseLong(value);
        return parsed == null ? null : parsed.intValue();
    }

    /**
     * 宽松 DECIMAL 解析：null/空白/非法均返回 null。
     *
     * @param value 原始字符串，可 null
     * @return BigDecimal 值或 null
     */
    private static BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 宽松 DATETIME 解析：按预置格式数组依次尝试，全部失败返回 null。
     *
     * @param value 原始字符串，可 null
     * @return LocalDateTime 值或 null
     */
    private static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        for (DateTimeFormatter formatter : DATETIME_FORMATS) {
            try {
                return LocalDateTime.parse(trimmed, formatter);
            } catch (DateTimeParseException ignored) {
                // 尝试下一种格式
            }
        }
        // 纯日期串（无时间部分）兜底为当日零点
        try {
            return LocalDateTime.of(LocalDate.parse(trimmed), LocalTime.MIDNIGHT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
