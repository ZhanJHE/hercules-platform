package com.zhanjh.hercules.sync.convert;

import com.zhanjh.hercules.model.Course;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CourseRowMapper 单元测试（阶段 B）：canal flatMessage 字符串行数据 → Course 领域对象的还原规则。
 *
 * <p>覆盖场景：主键缺失返回 null；DATETIME(3) 三种格式兼容；数值列宽松解析（非法置 null）；
 * 空白值置 null（NON_NULL 序列化下不参与 LWW 合并）。
 *
 * @author zhanjh
 * @since 0.0.1
 */
class CourseRowMapperTest {

    /**
     * 验证点：完整行（含毫秒时间戳）→ 全字段还原。
     */
    @Test
    void fullRowWithMillisDatetimeIsMapped() {
        Map<String, String> row = baseRow();
        row.put("update_time", "2026-09-01 09:00:00.123");

        Course course = CourseRowMapper.map(row);

        assertThat(course).isNotNull();
        assertThat(course.getId()).isEqualTo(1L);
        assertThat(course.getCourseCode()).isEqualTo("CS101");
        assertThat(course.getCourseName()).isEqualTo("程序设计基础");
        assertThat(course.getTeacherName()).isEqualTo("张伟");
        assertThat(course.getCredit()).isEqualByComparingTo("3.0");
        assertThat(course.getCapacity()).isEqualTo(60);
        assertThat(course.getEnrolled()).isEqualTo(88);
        assertThat(course.getScheduleJson()).isEqualTo("{\"day\":1}");
        assertThat(course.getUpdateTime()).isEqualTo(LocalDateTime.of(2026, 9, 1, 9, 0, 0, 123_000_000));
    }

    /**
     * 验证点：DATETIME 无毫秒格式与 ISO 格式（T 分隔）均可还原。
     */
    @Test
    void datetimeFormatsWithoutMillisAndIsoAreAccepted() {
        Map<String, String> noMillis = baseRow();
        noMillis.put("update_time", "2026-09-01 09:00:00");
        assertThat(CourseRowMapper.map(noMillis).getUpdateTime())
                .isEqualTo(LocalDateTime.of(2026, 9, 1, 9, 0, 0));

        Map<String, String> iso = baseRow();
        iso.put("update_time", "2026-09-01T09:00:00");
        assertThat(CourseRowMapper.map(iso).getUpdateTime())
                .isEqualTo(LocalDateTime.of(2026, 9, 1, 9, 0, 0));
    }

    /**
     * 验证点：id 缺失/非法 → 返回 null（调用方跳过该行，无法定位缓存键）。
     */
    @Test
    void missingOrInvalidIdReturnsNull() {
        assertThat(CourseRowMapper.map(new HashMap<>(Map.of("course_name", "无主键行")))).isNull();

        Map<String, String> badId = baseRow();
        badId.put("id", "abc");
        assertThat(CourseRowMapper.map(badId)).isNull();
    }

    /**
     * 验证点：宽松解析——非法数值列置 null、缺失列置 null，但对象仍可用（其余字段还原）。
     */
    @Test
    void lenientParsingNullsBadValuesButKeepsRest() {
        Map<String, String> row = baseRow();
        row.remove("teacher_name");
        row.put("credit", "N/A");
        row.put("capacity", "  ");

        Course course = CourseRowMapper.map(row);

        assertThat(course).isNotNull();
        assertThat(course.getTeacherName()).isNull();
        assertThat(course.getCredit()).isNull();
        assertThat(course.getCapacity()).isNull();
        assertThat(course.getEnrolled()).isEqualTo(88);
        assertThat(course.getCourseName()).isEqualTo("程序设计基础");
    }

    /**
     * 构造一行完整的基础行数据（时间列为不含毫秒的常见形态）。
     */
    private Map<String, String> baseRow() {
        Map<String, String> row = new HashMap<>();
        row.put("id", "1");
        row.put("course_code", "CS101");
        row.put("course_name", "程序设计基础");
        row.put("teacher_name", "张伟");
        row.put("credit", "3.0");
        row.put("capacity", "60");
        row.put("enrolled", "88");
        row.put("schedule_json", "{\"day\":1}");
        row.put("prerequisites_json", "[]");
        row.put("syllabus_url", "/syllabus/CS101.pdf");
        row.put("update_time", "2026-09-01 09:00:00");
        return row;
    }
}
