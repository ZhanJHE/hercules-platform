package com.zhanjh.hercules.sync.clock;

import com.zhanjh.hercules.common.JsonUtil;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 向量时钟：分布式写并发关系的判定与合并载体（FR-HC-03 核心创新点）。
 *
 * <p>内部以 {@link TreeMap} 维护「节点标识 → 逻辑计数器」的分量表，所有实例方法用
 * {@code synchronized} 保护，同一实例可被多线程并发读写（生产者构建 incoming 时钟、
 * 消费者读取存量时钟可能并发发生）。分量缺失等价于 0，因此新节点第一次自增即形成
 * 独立分量，无需预注册节点。</p>
 *
 * <p>核心机制：</p>
 * <ul>
 *   <li>increment：本节点分量 +1（缺失分量从 1 起算），表达「本节点发生一次版本写」；</li>
 *   <li>merge：与另一时钟逐分量取 max，只写入本实例、不修改入参（消费端先 {@link #copy()}
 *       再 merge，得到不覆盖任何一侧历史的「时钟并集」）；</li>
 *   <li>compare：静态比较支配关系并产出 {@link ClockRelation}——仅 a 大 → AFTER；
 *       仅 b 大 → BEFORE；互有大小 → CONCURRENT（并发冲突）；全等 → EQUAL；
 *       任一时钟为 null 按空时钟（全 0）参与比较。</li>
 * </ul>
 *
 * <p>持久化：经 {@link #toJson()} 序列化为 JSON 存入 t_cache_version.vector_clock_json，
 * 由 {@link #fromJson(String)} 还原；TreeMap 保证 JSON 分量按键名有序，便于人工比对与调试。</p>
 *
 * @author zhanjh
 * @since 0.0.1
 */
public class VectorClock {

    /** 时钟分量表：key 为节点标识（如 hercules.sync.node-id 配置值），value 为该节点逻辑计数器；TreeMap 使序列化结果按键有序。 */
    private final Map<String, Long> clocks = new TreeMap<>();

    /** 创建全空时钟（无任何分量），常作为 t_cache_version 无存量记录时的初始时钟。 */
    public VectorClock() {
    }

    /**
     * 以既有分量表创建时钟（拷贝入参，不与调用方共享底层 Map）。
     *
     * @param initial 初始分量表（节点标识 → 计数器），条目值为 null 时按 0 归一
     */
    public VectorClock(Map<String, Long> initial) {
        initial.forEach((k, v) -> clocks.put(k, v == null ? 0L : v)); // null 计数归一为 0，避免后续拆箱 NPE
    }

    /**
     * 本节点分量自增 1（缺失分量从 1 起算），表达「本节点发生一次版本写」。
     *
     * @param nodeId 本节点标识，通常取 hercules.sync.node-id 配置值
     * @return this，支持链式调用（如 versionReader.loadClock(key).increment(nodeId)）
     */
    public synchronized VectorClock increment(String nodeId) {
        clocks.merge(nodeId, 1L, Long::sum);
        return this;
    }

    /**
     * 与另一时钟逐分量取 max 合并进本实例（即「时钟并集」语义，不修改 other）。
     *
     * <p>消费端 CONCURRENT 分支先 {@link #copy()} 再 merge，得到同时保留新旧两侧历史、
     * 后续任一侧节点再写时都会被判为 AFTER 的合并时钟。</p>
     *
     * @param other 另一向量时钟，允许为 null（null 时本方法不做任何修改）
     * @return this，支持链式调用
     */
    public synchronized VectorClock merge(VectorClock other) {
        if (other != null) {
            other.clocks.forEach((k, v) -> clocks.merge(k, v, Long::max)); // 逐分量取 max，保留两侧最大历史
        }
        return this;
    }

    /**
     * 深拷贝当前时钟（新实例持有独立分量表，后续 increment/merge 互不影响）。
     *
     * @return 分量完全相同的新 VectorClock 实例
     */
    public synchronized VectorClock copy() {
        return new VectorClock(this.clocks);
    }

    /**
     * 返回当前分量的快照副本（外部修改副本不影响本实例内部状态）。
     *
     * @return 按节点名有序的分量表副本；空时钟返回空 Map，从不为 null
     */
    public synchronized Map<String, Long> snapshot() {
        return new TreeMap<>(clocks);
    }

    /**
     * 判断是否为空时钟（无任何分量）。
     *
     * @return 无任何分量返回 true；无存量版本记录反序列化出的初始时钟即为空
     */
    public synchronized boolean isEmpty() {
        return clocks.isEmpty();
    }

    /**
     * 比较两个向量时钟的支配关系（静态方法，基于两侧快照副本比较，不修改任何入参，天然线程安全）。
     *
     * <p>算法：对两时钟分量的并集逐项对比（缺失分量按 0），分别累计
     * 「a 存在更大分量」与「b 存在更大分量」两个布尔量：互有更大 → CONCURRENT；
     * 仅 a 更大 → AFTER；仅 b 更大 → BEFORE；全部相等 → EQUAL。</p>
     *
     * @param a 参与比较的时钟之一，允许为 null（按空时钟、全 0 处理）
     * @param b 参与比较的另一时钟，允许为 null（按空时钟、全 0 处理）
     * @return 支配关系判定结果，从不为 null
     */
    public static ClockRelation compare(VectorClock a, VectorClock b) {
        Map<String, Long> am = a == null ? Map.of() : a.snapshot();
        Map<String, Long> bm = b == null ? Map.of() : b.snapshot();
        boolean aGreater = false;
        boolean bGreater = false;
        for (String key : am.keySet()) {
            long av = am.get(key);
            long bv = bm.getOrDefault(key, 0L); // b 侧缺失的分量按 0 参与比较
            if (av > bv) {
                aGreater = true;
            }
        }
        for (String key : bm.keySet()) {
            long bv = bm.get(key);
            long av = am.getOrDefault(key, 0L); // a 侧缺失的分量按 0 参与比较
            if (bv > av) {
                bGreater = true;
            }
        }
        if (aGreater && bGreater) {
            return ClockRelation.CONCURRENT;
        }
        if (aGreater) {
            return ClockRelation.AFTER;
        }
        if (bGreater) {
            return ClockRelation.BEFORE;
        }
        return ClockRelation.EQUAL;
    }

    /**
     * 便捷判断两时钟是否并发（{@link #compare} 结果为 CONCURRENT）。
     *
     * @param a 时钟之一，允许为 null
     * @param b 另一时钟，允许为 null
     * @return 互不支配（并发冲突）返回 true，即消费端需要走冲突合并分支
     */
    public static boolean isConcurrent(VectorClock a, VectorClock b) {
        return compare(a, b) == ClockRelation.CONCURRENT;
    }

    /**
     * 序列化为 JSON，用于持久化到 t_cache_version.vector_clock_json。
     *
     * @return 形如 {"node-1":3,"node-sim":1} 的 JSON 字符串（按键名有序），空时钟为 "{}"；
     *         序列化失败时经 JsonUtil 抛出 IllegalStateException
     */
    public String toJson() {
        return JsonUtil.toJson(snapshot());
    }

    /**
     * 从 t_cache_version.vector_clock_json 的 JSON 反序列化还原时钟。
     *
     * <p>容错规则：null/空白字符串返回空时钟；非 Number 类型分量（如异常存量数据中的字符串值）
     * 直接丢弃；极端情况下重复 key 保留较大计数。注意：非法 JSON 会经 JsonUtil 抛出
     * {@link IllegalStateException}，不会静默返回空时钟。</p>
     *
     * @param json 存量时钟 JSON，允许为 null 或空白（对应无存量版本记录）
     * @return 还原后的 VectorClock；输入为 null/空白时为空时钟，从不为 null
     */
    public static VectorClock fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new VectorClock();
        }
        Map<String, Object> raw = JsonUtil.parseMap(json);
        // 过滤非 Number 分量（防御异常存量数据），重复 key 保留较大计数
        Map<String, Long> converted = raw.entrySet().stream()
                .filter(e -> e.getValue() instanceof Number)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> ((Number) e.getValue()).longValue(),
                        (x, y) -> Math.max(x, y), TreeMap::new));
        return new VectorClock(converted);
    }
}
