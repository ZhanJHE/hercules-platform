package com.zhanjh.hercules.common;

import java.util.Locale;

/**
 * 缓存键约定（工具类）：统一详情键与列表键的拼接规则，读写路径与 TTL 策略（FixedTTLStrategy 按列表前缀区分两类键）共同遵守。
 *
 * <p>两类键的治理策略不同：
 * <ul>
 *   <li>详情键 course:{id} —— 纳入向量时钟版本链（t_cache_version.cache_key 即此键）：写路径发布 VersionedValue，
 *       消费者比对存量时钟后写 Redis 并失效 L1，数据变更即时生效；</li>
 *   <li>列表键 course:list:{p}:{s}:{kw} —— 不纳入版本链，仅靠短 TTL（默认 10s，见 hercules.cache.list-ttl）
 *       过期实现最终一致：选课导致的 enrolled 变化不主动失效列表，最多延迟一个 TTL 窗口可见。</li>
 * </ul>
 *
 * <p>无状态工具类，私有构造器禁止实例化。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public final class CacheKeys {

    /** 工具类，禁止实例化。 */
    private CacheKeys() {
    }

    /* 说明：lowercase 归一使用 Locale.ROOT，避免土耳其语等区域设置对 I/i 的特例转换改变键语义。 */

    /**
     * 课程详情键，纳入向量时钟版本链。
     *
     * @param id 课程主键，非 null
     * @return 形如 "course:1" 的缓存键
     */
    public static String course(Long id) {
        return "course:" + id;
    }

    /**
     * 课程列表键，短 TTL 最终一致，不纳入版本链。
     *
     * @param page    页码，从 1 起（与 Controller @RequestParam 默认值一致）
     * @param size    每页条数（默认 10）
     * @param keyword 搜索关键词（匹配课程名/课程编码/教师名），可为 null
     * @return 形如 "course:list:1:10:-"（无关键词）或 "course:list:1:10:java" 的缓存键；
     *         关键词统一转小写（MySQL 默认排序规则大小写不敏感，"Java" 与 "java" 回源结果一致，
     *         归一后共享同一缓存条目，消除键分裂），且 ":" 与 "%" 做百分号转义，保证键段结构无歧义
     */
    public static String courseList(int page, int size, String keyword) {
        // 无关键词时以 "-" 占位避免键尾空段；其余 trim 后统一转小写——
        // DB 侧 LIKE 在大小写不敏感排序规则下两写法结果一致，键归一不改变语义、只消除键分裂
        String kw = (keyword == null || keyword.isBlank()) ? "-" : keyword.trim().toLowerCase(Locale.ROOT);
        // ":" 会与键段分隔符混淆（如 kw="a:1" 可与其他 page/size 组合拼出结构相同的键）、
        // "%" 是本转义方案的转义字符，二者均需转义以杜绝键段碰撞
        String escaped = kw.replace("%", "%25").replace(":", "%3A");
        return "course:list:" + page + ":" + size + ":" + escaped;
    }
}
