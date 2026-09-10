package com.zhanjh.hercules.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhanjh.hercules.common.JsonUtil;
import com.zhanjh.hercules.model.Course;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 排课冲突智能体（阶段 D，纯规则计算）：时间片重叠、先修课要求、学分合计。
 *
 * <p>设计要点：冲突判定是<b>确定性问题</b>，用代码计算而非 LLM——结果可复现、零 token、
 * 可单测覆盖；LLM 只负责把校验结论转述给用户。
 *
 * <p>判定规则（对《起步文档》FR-MA-04 的 MVP 落地口径）：
 * <ul>
 *   <li><b>时间片重叠</b>：schedule_json 形如 {"day":1,"sections":[5,6]}（周几 + 节次），
 *       同一天且节次有交集 → 冲突；无排课信息（解析失败/空）的课程不参与时间判定；</li>
 *   <li><b>先修课</b>：prerequisites_json 为<b>先修课程主键 ID 数组</b>（起步文档 §5.1.1 设计），
 *       候选课的先修 ID 不在学生当前已选课程 ID 集合中 → 冲突（文案提示先修课程编号）；
 *       种子数据均为空数组，先修判定主要供后续学期数据使用；</li>
 *   <li><b>学分合计</b>：仅作为信息性输出（选课后总学分），不做上限拦截——培养方案数据
 *       尚未建模，上限校验待后续学期补充（已记录边界）。</li>
 * </ul>
 *
 * <p>线程安全性：无实例可变状态（仅静态解析），可并发调用。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Component
public class SchedulingAgent {

    /**
     * 冲突校验结果。
     *
     * @param passed          是否无冲突（true 才允许进入确认环节）
     * @param conflicts       冲突明细（面向用户的文案）
     * @param totalCreditsAfter 选课成功后的总学分（信息性）
     * @author zhanjh
     * @since 0.0.1
     */
    public record ConflictCheck(boolean passed, List<String> conflicts, int totalCreditsAfter) {
    }

    /**
     * 校验候选课程与学生当前已选课程的冲突。
     *
     * @param candidate 候选课程，不应为 null
     * @param enrolled  学生当前有效选课（status=1），可为空列表
     * @return 校验结果（从不为 null）
     */
    public ConflictCheck check(Course candidate, List<Course> enrolled) {
        List<String> conflicts = new ArrayList<>();
        ScheduleSlot candidateSlot = parseSchedule(candidate.getScheduleJson());

        Set<Long> enrolledIds = new HashSet<>();
        int creditsAfter = candidate.getCredit() == null ? 0 : candidate.getCredit().intValue();
        for (Course taken : enrolled) {
            if (taken.getId() != null && taken.getId().equals(candidate.getId())) {
                // 同一门课程不构成时间冲突（重复选课由唯一键兜底，文案由执行层给出）
                continue;
            }
            if (taken.getId() != null) {
                enrolledIds.add(taken.getId());
            }
            if (taken.getCredit() != null) {
                creditsAfter += taken.getCredit().intValue();
            }
            ScheduleSlot slot = parseSchedule(taken.getScheduleJson());
            if (candidateSlot != null && slot != null && candidateSlot.overlaps(slot)) {
                conflicts.add("与《" + displayName(taken) + "》上课时间冲突（周"
                        + candidateSlot.day() + "，节次 " + candidateSlot.sections() + "）");
            }
        }

        for (String prerequisiteId : parsePrerequisites(candidate.getPrerequisitesJson())) {
            Long prerequisite = parseLongOrNull(prerequisiteId);
            if (prerequisite != null && !enrolledIds.contains(prerequisite)) {
                conflicts.add("需先修课程编号：" + prerequisiteId);
            }
        }

        return new ConflictCheck(conflicts.isEmpty(), conflicts, creditsAfter);
    }

    /**
     * 解析 schedule_json 为排课槽位；JSON 缺失/非法/无节次 → null（不参与时间判定）。
     *
     * @param scheduleJson 原始 JSON 字符串，可 null
     * @return 排课槽位或 null
     */
    ScheduleSlot parseSchedule(String scheduleJson) {
        if (scheduleJson == null || scheduleJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JsonUtil.mapper().readTree(scheduleJson);
            if (!node.hasNonNull("day") || !node.path("sections").isArray()
                    || !node.path("sections").iterator().hasNext()) {
                return null;
            }
            int day = node.path("day").asInt();
            Set<Integer> sections = new HashSet<>();
            node.path("sections").forEach(s -> sections.add(s.asInt()));
            return sections.isEmpty() ? null : new ScheduleSlot(day, sections);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 prerequisites_json 为先修课程 ID 字符串列表；JSON 缺失/非法 → 空列表。
     *
     * @param prerequisitesJson 原始 JSON 字符串，可 null
     * @return 先修课程 ID 列表（从不为 null）
     */
    List<String> parsePrerequisites(String prerequisitesJson) {
        if (prerequisitesJson == null || prerequisitesJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = JsonUtil.mapper().readTree(prerequisitesJson);
            List<String> ids = new ArrayList<>();
            node.forEach(n -> ids.add(n.asText()));
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 宽松 Long 解析（先修 ID 为数字字符串；非数字先修值视为未满足并原样提示）。
     *
     * @param value 原始字符串
     * @return Long 值；非数字返回 null
     */
    private Long parseLongOrNull(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 课程显示名（名称优先，缺失退回编码）。
     *
     * @param course 课程
     * @return 显示名
     */
    private String displayName(Course course) {
        return course.getCourseName() == null ? course.getCourseCode() : course.getCourseName();
    }

    /**
     * 排课槽位：周几 + 节次集合。
     *
     * @param day      周几（1-7）
     * @param sections 节次集合
     * @author zhanjh
     * @since 0.0.1
     */
    record ScheduleSlot(int day, Set<Integer> sections) {

        /**
         * 两个槽位是否时间重叠（同一天且节次有交集）。
         *
         * @param other 另一槽位
         * @return true-重叠
         */
        boolean overlaps(ScheduleSlot other) {
            return this.day == other.day && !java.util.Collections.disjoint(this.sections, other.sections);
        }
    }
}
