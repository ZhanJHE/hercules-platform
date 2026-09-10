package com.zhanjh.hercules.agent.core;

import com.zhanjh.hercules.model.Course;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SchedulingAgent 单元测试（阶段 D，纯规则）：时间片重叠、先修课、学分合计、脏数据容忍。
 *
 * @author zhanjh
 * @since 0.0.1
 */
class SchedulingAgentTest {

    /** 被测对象。 */
    private final SchedulingAgent schedulingAgent = new SchedulingAgent();

    /**
     * 验证点：同一天节次有交集 → 时间冲突；不同天 → 无冲突。
     */
    @Test
    void timeOverlapOnSameDayIsDetected() {
        Course candidate = course(1L, "CS101", "{\"day\":1,\"sections\":[5,6]}", "[]", "3.0");
        Course takenSameSlot = course(2L, "MA101", "{\"day\":1,\"sections\":[6,7]}", "[]", "4.0");
        Course takenOtherDay = course(3L, "GE201", "{\"day\":2,\"sections\":[5,6]}", "[]", "2.0");

        SchedulingAgent.ConflictCheck withSame = schedulingAgent.check(candidate, List.of(takenSameSlot));
        assertThat(withSame.passed()).isFalse();
        assertThat(withSame.conflicts()).anyMatch(c -> c.contains("时间冲突"));

        SchedulingAgent.ConflictCheck withOther = schedulingAgent.check(candidate, List.of(takenOtherDay));
        assertThat(withOther.passed()).isTrue();
    }

    /**
     * 验证点：先修课（prerequisites_json 存课程主键 ID 列表）未满足 → 冲突；已选包含该 ID → 满足。
     */
    @Test
    void unmetPrerequisiteIsDetected() {
        Course candidate = course(4L, "CS301", "[]", "[\"1\"]", "3.0");

        SchedulingAgent.ConflictCheck without = schedulingAgent.check(candidate, List.of());
        assertThat(without.passed()).isFalse();
        assertThat(without.conflicts()).anyMatch(c -> c.contains("需先修课程编号：1"));

        Course done = course(1L, "CS101", "[]", "[]", "3.0");
        SchedulingAgent.ConflictCheck with = schedulingAgent.check(candidate, List.of(done));
        assertThat(with.passed()).isTrue();
    }

    /**
     * 验证点：学分合计为信息性输出（候选 + 已选之和）。
     */
    @Test
    void totalCreditsSumIsReported() {
        Course candidate = course(5L, "CS201", "[]", "[]", "3.0");
        Course taken = course(2L, "MA101", "[]", "[]", "4.5");

        SchedulingAgent.ConflictCheck check = schedulingAgent.check(candidate, List.of(taken));

        assertThat(check.totalCreditsAfter()).isEqualTo(7); // 3.0 + 4.5 → 整数合计 7
    }

    /**
     * 验证点：脏数据容忍——schedule/prerequisites 非法 JSON 或空值不抛异常、不判冲突；
     * 与候选相同 id 的已选课程不计时间冲突。
     */
    @Test
    void malformedJsonAndSameCourseAreTolerated() {
        Course candidate = course(1L, "CS101", "{bad json", "[bad", "3.0");
        Course sameCourse = course(1L, "CS101", "{\"day\":1,\"sections\":[5,6]}", "[]", "3.0");

        SchedulingAgent.ConflictCheck check = schedulingAgent.check(candidate, List.of(sameCourse));

        assertThat(check.passed()).isTrue();
        assertThat(check.conflicts()).isEmpty();
    }

    /**
     * 构造课程对象（参数对齐 data.sql 列语义）。
     */
    private Course course(Long id, String code, String scheduleJson, String prerequisitesJson, String credit) {
        Course course = new Course();
        course.setId(id);
        course.setCourseCode(code);
        course.setCourseName("课程" + code);
        course.setCredit(new BigDecimal(credit));
        course.setScheduleJson(scheduleJson);
        course.setPrerequisitesJson(prerequisitesJson);
        return course;
    }
}
