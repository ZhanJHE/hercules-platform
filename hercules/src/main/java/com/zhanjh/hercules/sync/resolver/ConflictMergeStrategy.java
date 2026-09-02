package com.zhanjh.hercules.sync.resolver;

/**
 * 并发冲突合并策略接口：决定 CONCURRENT 分支中旧值与新值如何合成最终值（策略模式）。
 *
 * <p>由 VersionChangeConsumer 在向量时钟判定并发时调用；MVP 默认实现为
 * {@link FieldLwwMergeStrategy}（字段级 LWW）。Sprint 2 若引入语义级合并
 * （如计数器相加、集合并集），实现本接口即可替换，消费端决策表不变。</p>
 *
 * @author zhanjh
 * @since 0.0.1
 */
public interface ConflictMergeStrategy {

    /**
     * 将旧值与新值合并为最终缓存值（契约：不修改入参，只依据内容与时间戳做裁决）。
     *
     * <p>实现约定：oldValueJson 为 null/空白（L2 无现值）时直接返回 newValueJson；
     * 两侧 JSON 均为扁平对象，按字段裁决；无法解析的非法 JSON 由 JsonUtil 抛出
     * IllegalStateException，由调用方（AFTER_COMMIT 监听器）兜底捕获。</p>
     *
     * @param oldValueJson 当前生效值（JSON 对象字符串；可能为 null 或空白：L2 无值）
     * @param oldTimestamp 当前生效值的应用时间（epoch millis，取自 t_cache_version.update_time；无记录按 0）
     * @param newValueJson 到达的新值（JSON 对象字符串，非空）
     * @param newTimestamp 新值产生时间（epoch millis，来源节点的写入时刻）
     * @return 合并后的 JSON 对象字符串，从不为 null
     */
    String merge(String oldValueJson, long oldTimestamp, String newValueJson, long newTimestamp);
}
