package com.zhanjh.hercules.sync.resolver;

import com.zhanjh.hercules.common.JsonUtil;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 字段级 LWW（Last-Write-Wins）冲突合并策略：并发冲突的最小裁决单位是 JSON 字段而非整个值。
 *
 * <p>合并算法：以旧值字段为基底，遍历新值字段取并集——</p>
 * <ol>
 *   <li>字段仅出现在新值：直接并入（并集语义，与时间戳无关）；</li>
 *   <li>字段两侧都有且值不同：按时间戳裁决，newTimestamp >= oldTimestamp 时新值覆盖，
 *       否则保留旧值（两时间戳相等视为平局，新值胜出）；</li>
 *   <li>字段两侧都有且值相同：无操作；</li>
 *   <li>字段仅出现在旧值：保留不动。</li>
 * </ol>
 *
 * <p>行为要点：oldValueJson 为 null/空白时无法做字段级合并，直接原样返回 newValueJson
 * （整体退化为「新值全胜」）。合并结果由 LinkedHashMap 承载，保留旧值字段顺序、新字段追加在后。
 * 线程安全性：无实例状态，可并发调用。</p>
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Component
public class FieldLwwMergeStrategy implements ConflictMergeStrategy {

    /**
     * 执行字段级 LWW 合并（算法步骤见类注释）。
     *
     * @param oldValueJson 当前生效值 JSON；null 或空白时直接返回 newValueJson
     * @param oldTimestamp 旧值应用时间（epoch millis，取自 t_cache_version.update_time）
     * @param newValueJson 到达的新值 JSON，非空
     * @param newTimestamp 新值产生时间（epoch millis，来源节点的写入时刻）
     * @return 合并后的 JSON 对象字符串，从不为 null；入参为非法 JSON 时经 JsonUtil 抛出 IllegalStateException
     */
    @Override
    public String merge(String oldValueJson, long oldTimestamp, String newValueJson, long newTimestamp) {
        // 旧值缺失（L2 已被逐出或首次写入）时无法做字段级合并，整体采用新值
        if (oldValueJson == null || oldValueJson.isBlank()) {
            return newValueJson;
        }
        Map<String, Object> merged = new LinkedHashMap<>(JsonUtil.parseMap(oldValueJson));
        Map<String, Object> incoming = JsonUtil.parseMap(newValueJson);
        // 平局（两时间戳相等）让新值胜出：新值刚到达，重复投递场景下覆盖结果一致
        boolean newerWins = newTimestamp >= oldTimestamp;

        for (Map.Entry<String, Object> entry : incoming.entrySet()) {
            String field = entry.getKey();
            Object newVal = entry.getValue();
            Object oldVal = merged.get(field);
            if (oldVal == null) {
                merged.put(field, newVal); // 仅新值存在的字段：无条件并入（字段并集语义）
            } else if (!oldVal.equals(newVal) && newerWins) {
                merged.put(field, newVal); // 同字段值不同：按 LWW 裁决；值相同或旧值时间更新则保持不变
            }
        }
        return JsonUtil.toJson(merged);
    }
}
