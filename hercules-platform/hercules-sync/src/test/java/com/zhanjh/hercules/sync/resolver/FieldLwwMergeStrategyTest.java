package com.zhanjh.hercules.sync.resolver;

import com.zhanjh.hercules.common.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FieldLwwMergeStrategy 纯单元测试：验证字段级 LWW 合并对「字段值冲突、字段缺失、字段值相同」三类情形的裁决。
 *
 * <p>被测对象：FieldLwwMergeStrategy.merge——合并结果为两边字段并集；仅当同一字段值不同时按时间戳裁决
 * （新时间戳 ≥ 旧时间戳则新值获胜），仅出现于一侧的字段直接保留、与时间戳无关。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>{@code nullOldValueReturnsNewValue}：旧值为 null（L2 无现值）时原样返回新值 JSON；</li>
 *   <li>{@code newerTimestampWinsConflictingField}：冲突字段按时间戳取新值，旧值独有字段保留；</li>
 *   <li>{@code olderTimestampKeepsOldConflictingField}：新时间戳更旧 → 冲突字段保留旧值；</li>
 *   <li>{@code fieldOnlyInNewValueIsAdded}：仅新值持有的字段即使在时间戳劣势下也补齐进结果；</li>
 *   <li>{@code equalFieldValuesStayUntouched}：同字段同值不触发改写，旧值独有字段保留。</li>
 * </ul>
 *
 * @author zhanjh
 * @since 0.0.1
 */
class FieldLwwMergeStrategyTest {

    /** 被测策略实例：无状态、线程安全，可跨用例复用。 */
    private final FieldLwwMergeStrategy strategy = new FieldLwwMergeStrategy();

    /**
     * 验证点：旧值为 null 时不做合并，直接原样返回新值 JSON（含字段 a=1）。
     */
    @Test
    void nullOldValueReturnsNewValue() {
        String result = strategy.merge(null, 0L, "{\"a\":1}", 100L);
        assertThat(JsonUtil.parseMap(result)).containsEntry("a", 1);
    }

    /**
     * 验证点：同字段值不同且新时间戳（200）晚于旧（100）→ courseName 取新值「新名」，
     * 仅旧值持有的 teacherName=张伟 原样保留。
     */
    @Test
    void newerTimestampWinsConflictingField() {
        String old = JsonUtil.toJson(Map.of("courseName", "旧名", "teacherName", "张伟"));
        String newJson = JsonUtil.toJson(Map.of("courseName", "新名"));
        String merged = strategy.merge(old, 100L, newJson, 200L);
        Map<String, Object> map = JsonUtil.parseMap(merged);
        assertThat(map).containsEntry("courseName", "新名").containsEntry("teacherName", "张伟");
    }

    /**
     * 验证点：同字段值不同但新时间戳（100）早于旧（500）→ courseName 保留旧值「旧名」。
     */
    @Test
    void olderTimestampKeepsOldConflictingField() {
        String old = JsonUtil.toJson(Map.of("courseName", "旧名"));
        String newJson = JsonUtil.toJson(Map.of("courseName", "新名"));
        String merged = strategy.merge(old, 500L, newJson, 100L);
        assertThat(JsonUtil.parseMap(merged)).containsEntry("courseName", "旧名");
    }

    /**
     * 验证点：仅新值持有的字段（syllabusUrl）按并集语义直接补齐——
     * 即使新时间戳（100）早于旧（500）也补入，该分支不受 LWW 时间裁决约束。
     */
    @Test
    void fieldOnlyInNewValueIsAdded() {
        String old = JsonUtil.toJson(Map.of("courseName", "旧名"));
        String newJson = JsonUtil.toJson(Map.of("courseName", "旧名", "syllabusUrl", "/s.pdf"));
        String merged = strategy.merge(old, 500L, newJson, 100L);
        assertThat(JsonUtil.parseMap(merged)).containsEntry("syllabusUrl", "/s.pdf");
    }

    /**
     * 验证点：同字段同值（courseName=同名）不触发改写；仅旧值持有的 credit=3.0 保留，
     * 佐证合并以旧值为基线做增量覆盖。
     */
    @Test
    void equalFieldValuesStayUntouched() {
        String old = JsonUtil.toJson(Map.of("courseName", "同名", "credit", 3.0));
        String newJson = JsonUtil.toJson(Map.of("courseName", "同名"));
        String merged = strategy.merge(old, 100L, newJson, 200L);
        assertThat(JsonUtil.parseMap(merged)).containsEntry("credit", 3.0);
    }
}
