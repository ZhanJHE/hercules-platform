package com.zhanjh.hercules.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 缓存版本记录实体，对应起步文档 §5.2.1 的 t_cache_version 表，每个纳入版本链的缓存键一行（cache_key 唯一）。
 *
 * <p>向量时钟冲突消解（FR-HC-03）的持久化锚点：
 * <ul>
 *   <li>生产侧：VersionReader 按 cache_key 读取 vector_clock_json，构建「存量时钟 + 本节点 +1」的 incoming 时钟；</li>
 *   <li>消费侧：VersionChangeConsumer 将到达时钟与存量时钟 compare —— AFTER 应用、EQUAL/BEFORE 丢弃（幂等）、
 *       CONCURRENT 走字段级 LWW 合并；应用后 upsert 本行：首次插入 currentVersion=1，此后每应用一版 +1。</li>
 * </ul>
 *
 * <p>当前仅详情键 course:{id} 有对应行；列表键不纳入版本链。updateTime 在 CONCURRENT 合并时
 * 还作为旧值的时间戳参与 LWW 新旧判定（见 VersionChangeConsumer.resolveConflict）。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@TableName("t_cache_version")
public class CacheVersion {

    /** 主键，数据库自增（t_cache_version.id，IdType.AUTO）。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 缓存键，全局唯一（t_cache_version.cache_key），当前仅详情键，如 "course:1"。 */
    private String cacheKey;

    /** 产生最近一次被应用变更的节点标识，即向量时钟分量名，如 "node-1"（t_cache_version.node_id）。 */
    private String nodeId;

    /** 当前逻辑版本号：首次插入为 1，此后每应用一个新版本 +1（t_cache_version.current_version）。 */
    private Long currentVersion;

    /** 完整向量时钟 JSON（t_cache_version.vector_clock_json，TEXT），形如 {"node-1":3,"node-2":1}，经 VectorClock.toJson/fromJson 互转。 */
    private String vectorClockJson;

    /** 本行最近更新时间，由消费端以 LocalDateTime.now() 写入（t_cache_version.update_time）；CONCURRENT 合并时作为旧值时间戳。 */
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCacheKey() {
        return cacheKey;
    }

    public void setCacheKey(String cacheKey) {
        this.cacheKey = cacheKey;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public Long getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(Long currentVersion) {
        this.currentVersion = currentVersion;
    }

    public String getVectorClockJson() {
        return vectorClockJson;
    }

    public void setVectorClockJson(String vectorClockJson) {
        this.vectorClockJson = vectorClockJson;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
