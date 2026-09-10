package com.zhanjh.hercules.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓存键约定单元测试（遗留事项清理）：验证列表键的大小写归一、空白占位与键段转义。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>详情键格式；</li>
 *   <li>无关键词（null/空白）→ "-" 占位；</li>
 *   <li>关键词 trim + 大小写归一（"Java"/"java" 命中同一键，MySQL 默认排序规则大小写不敏感，
 *       归一不改变语义、只消除键分裂）；</li>
 *   <li>":" 与 "%" 转义回归（防止键段碰撞）。</li>
 * </ul>
 *
 * @author zhanjh
 * @since 0.0.1
 */
class CacheKeysTest {

    /**
     * 验证点：详情键为 course:{id} 格式。
     */
    @Test
    void detailKeyFollowsPrefixIdFormat() {
        assertThat(CacheKeys.course(1L)).isEqualTo("course:1");
        assertThat(CacheKeys.course(39L)).isEqualTo("course:39");
    }

    /**
     * 验证点：null 与空白关键词均以 "-" 占位，命中同一个"无关键词"列表键。
     */
    @Test
    void nullOrBlankKeywordUsesDashPlaceholder() {
        assertThat(CacheKeys.courseList(1, 10, null)).isEqualTo("course:list:1:10:-");
        assertThat(CacheKeys.courseList(1, 10, "   ")).isEqualTo("course:list:1:10:-");
    }

    /**
     * 验证点：关键词 trim 且大小写归一——同一词的不同写法共享同一缓存条目。
     */
    @Test
    void keywordIsTrimmedAndCaseNormalized() {
        assertThat(CacheKeys.courseList(1, 10, "  Java ")).isEqualTo("course:list:1:10:java");
        assertThat(CacheKeys.courseList(2, 10, "JAVA")).isEqualTo(CacheKeys.courseList(2, 10, "java"));
        assertThat(CacheKeys.courseList(2, 10, "数据结构")).isEqualTo(CacheKeys.courseList(2, 10, "  数据结构  "));
    }

    /**
     * 验证点：":" 与 "%" 仍按既有规则转义，键段结构无歧义（先小写归一再转义，故 "%3A" → "%253a"）。
     */
    @Test
    void colonAndPercentAreEscaped() {
        assertThat(CacheKeys.courseList(1, 10, "a:1")).isEqualTo("course:list:1:10:a%3A1");
        assertThat(CacheKeys.courseList(1, 10, "100%")).isEqualTo("course:list:1:10:100%25");
        assertThat(CacheKeys.courseList(1, 10, "%3A")).isEqualTo("course:list:1:10:%253a");
    }
}
