package com.zhanjh.hercules.sync.consumer;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zhanjh.hercules.cache.local.LocalCacheManager;
import com.zhanjh.hercules.cache.remote.DistributedCacheManager;
import com.zhanjh.hercules.cache.stats.CacheStatsCollector;
import com.zhanjh.hercules.cache.strategy.TTLStrategy;
import com.zhanjh.hercules.mapper.CacheVersionMapper;
import com.zhanjh.hercules.model.CacheVersion;
import com.zhanjh.hercules.sync.clock.ClockRelation;
import com.zhanjh.hercules.sync.clock.VectorClock;
import com.zhanjh.hercules.sync.config.HerculesSyncProperties;
import com.zhanjh.hercules.sync.model.VersionedValue;
import com.zhanjh.hercules.sync.resolver.ConflictMergeStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 版本变更消费者：同步链路的核心决策点，实现向量时钟冲突消解决策表（FR-HC-03）。
 *
 * <p>对每条到达的 {@link VersionedValue}，先按 cache_key 从 t_cache_version 读取存量版本记录
 * 并还原存量向量时钟，再用 {@link VectorClock#compare} 比较 incoming 与存量时钟，按下表决策：</p>
 * <ul>
 *   <li>AFTER（新版本支配存量）→ 直接应用：写 L2（Redis，TTL 由 TTLStrategy 决定）
 *       + 失效 L1（Caffeine，触发回源）+ upsert 版本记录；</li>
 *   <li>EQUAL / BEFORE（重复或过期消息）→ 丢弃，仅记 debug 日志，保证消费幂等；</li>
 *   <li>CONCURRENT（并发冲突）→ 从 L2 取现值、以 t_cache_version.update_time 作旧值时间戳，
 *       交 {@link ConflictMergeStrategy}（默认字段级 LWW）合并；时钟取并集
 *       （copy + merge）后应用，并额外计入冲突指标。</li>
 * </ul>
 *
 * <p>指标口径：每成功应用一个版本 versionApplied + 1；CONCURRENT 应用在此外额外
 * conflictDetected + 1（经 CacheStatsCollector 暴露为 Micrometer 指标）。upsert 时若时钟
 * 分量数超过 hercules.sync.max-nodes（默认 10）打 warn，提示按风险 R-03 规划归档。</p>
 *
 * <p>线程安全性：无共享可变状态（依赖均为无状态线程安全组件），可被并发调用；
 * MVP 中由 AFTER_COMMIT 监听器在发布线程上串行驱动。</p>
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Component
public class VersionChangeConsumer {

    private static final Logger log = LoggerFactory.getLogger(VersionChangeConsumer.class);

    /** t_cache_version 表 Mapper：读写存量版本记录（vector_clock_json 即向量时钟持久化位置）。 */
    private final CacheVersionMapper cacheVersionMapper;
    /** L2 分布式缓存（Redis）：应用新值的目标，也是冲突合并时旧值的读取来源。 */
    private final DistributedCacheManager remote;
    /** L1 本地缓存（Caffeine）：应用新值后整体失效，下次读取从 L2 回填。 */
    private final LocalCacheManager local;
    /** 冲突合并策略：CONCURRENT 分支的字段级裁决器，默认注入 FieldLwwMergeStrategy。 */
    private final ConflictMergeStrategy mergeStrategy;
    /** TTL 策略：应用新值时提供 L2 的过期时间。 */
    private final TTLStrategy ttlStrategy;
    /** 缓存统计收集器：累计 versionApplied / conflictDetected 指标。 */
    private final CacheStatsCollector stats;
    /** 同步模块配置：提供 maxNodes 阈值（upsert 时超限告警，R-03）等参数。 */
    private final HerculesSyncProperties syncProps;

    public VersionChangeConsumer(CacheVersionMapper cacheVersionMapper,
                                 DistributedCacheManager remote,
                                 LocalCacheManager local,
                                 ConflictMergeStrategy mergeStrategy,
                                 TTLStrategy ttlStrategy,
                                 CacheStatsCollector stats,
                                 HerculesSyncProperties syncProps) {
        this.cacheVersionMapper = cacheVersionMapper;
        this.remote = remote;
        this.local = local;
        this.mergeStrategy = mergeStrategy;
        this.ttlStrategy = ttlStrategy;
        this.stats = stats;
        this.syncProps = syncProps;
    }

    /**
     * 处理一条版本变更消息：冲突消解决策表入口（AFTER 应用 / CONCURRENT 合并 / EQUAL、BEFORE 丢弃）。
     *
     * <p>处理步骤：按 cache_key 查存量版本记录（无记录则存量时钟为空时钟）→ 还原存量时钟 →
     * 与 incoming 时钟比较 → 按支配关系分支处理。</p>
     *
     * @param incoming 到达的版本化值，其时钟应已包含来源节点的自增分量；不应为 null
     */
    public void onMessage(VersionedValue incoming) {
        // cache_key 为唯一业务键，同一 key 至多一行存量版本记录
        CacheVersion stored = cacheVersionMapper
                .selectOne(new QueryWrapper<CacheVersion>().eq("cache_key", incoming.key()));
        // 无存量记录视为空时钟：与任意非空 incoming 比较必为 AFTER，首次写直接应用
        VectorClock storedClock = stored == null
                ? new VectorClock()
                : VectorClock.fromJson(stored.getVectorClockJson());

        ClockRelation relation = VectorClock.compare(incoming.clock(), storedClock);
        switch (relation) {
            case AFTER -> apply(incoming, stored, incoming.clock(), false);
            case CONCURRENT -> resolveConflict(incoming, stored, storedClock);
            // EQUAL/BEFORE：重复投递或过期消息，直接丢弃保证幂等，不重复应用、不回退已合并结果
            case EQUAL, BEFORE -> log.debug(
                    "[hercules-sync] discard stale/duplicate version for key={} (node={}, relation={})",
                    incoming.key(), incoming.nodeId(), relation);
        }
    }

    /**
     * 应用一个最终版本：刷新 L2、失效 L1、upsert 版本记录并累计指标。
     *
     * <p>实现要点：先写 L2 再失效 L1，保证 L1 失效后的回源读到的一定是新值；
     * 版本记录持久化 clockToStore（直接应用时为 incoming 时钟，冲突合并后为并集时钟），
     * 作为下一次消息比较的存量基准。</p>
     *
     * @param value        待应用的版本化值（冲突分支传入的是合并后新建的 VersionedValue）
     * @param stored       存量版本记录，可能为 null（首次应用时 upsert 走 insert）
     * @param clockToStore 本次要持久化的向量时钟
     * @param conflict     是否由并发冲突合并产生；true 时额外累计 conflictDetected 指标并调整日志措辞
     */
    private void apply(VersionedValue value, CacheVersion stored, VectorClock clockToStore, boolean conflict) {
        // 先写 L2 再失效 L1：L1 回源时从 L2 拿到的一定是本次应用的新值
        remote.put(value.key(), value.valueJson(), ttlStrategy.remoteTtl(value.key()));
        local.invalidate(value.key());
        // upsert 以本次时钟覆盖存量，作为下一次消息比较的基准
        upsert(value, stored, clockToStore);
        stats.versionApplied();
        if (conflict) {
            stats.conflictDetected();
        }
        log.info("[hercules-sync] {} applied for key={} (node={}, clock={})",
                conflict ? "merged concurrent version" : "new version",
                value.key(), value.nodeId(), clockToStore.toJson());
    }

    /**
     * 并发冲突消解：字段级 LWW 合并旧值与新值后，按合并结果应用。
     *
     * <p>实现要点：旧值取自 L2（remote.get，可能为 null，此时合并策略直接采用新值）；
     * 旧值时间戳取 t_cache_version.update_time（按系统默认时区转 epoch millis），
     * 记录为空或时间为 null 时按 0 处理（新值必胜）；合并时钟 = 存量时钟 copy 后
     * merge incoming，同时保留两侧历史分量，保证后续任一节点的下一次写都能被判为 AFTER。</p>
     *
     * @param incoming    到达的冲突版本
     * @param stored      存量版本记录（实际必有值：无记录时空存量时钟只会得到 AFTER/EQUAL，不会进入本分支）
     * @param storedClock 从存量记录还原的向量时钟
     */
    private void resolveConflict(VersionedValue incoming, CacheVersion stored, VectorClock storedClock) {
        String oldJson = remote.get(incoming.key()); // 旧值取自 L2；L2 已被逐出时为 null，合并退化为整体采用新值
        // LWW 裁决时间戳：以版本记录 update_time 近似旧值的应用时刻；缺失按 0 让新值必胜
        long oldTs = stored == null || stored.getUpdateTime() == null
                ? 0L
                : stored.getUpdateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        String mergedJson = mergeStrategy.merge(oldJson, oldTs, incoming.valueJson(), incoming.timestamp());
        // 时钟并集：先 copy 再 merge，不破坏任何一侧的历史分量
        VectorClock mergedClock = storedClock.copy().merge(incoming.clock());
        apply(new VersionedValue(incoming.key(), mergedJson, incoming.nodeId(),
                incoming.timestamp(), mergedClock), stored, mergedClock, true);
    }

    /**
     * 新增或更新 t_cache_version 版本记录（以 cache_key 为唯一业务键）。
     *
     * <p>实现要点：insert 时 currentVersion 从 1 起算；update 时 currentVersion 原值 + 1、
     * nodeId 覆盖为最新写入节点、update_time 刷新为当前时间（该字段同时充当下次冲突合并
     * 的旧值 LWW 时间戳）。写入前检查时钟分量数，超过 hercules.sync.max-nodes（默认 10）
     * 打 warn——对应风险 R-03：节点数无上限增长会膨胀 vector_clock_json 并拖慢比较，规划归档收敛。</p>
     *
     * @param value        已决断要应用的版本化值（提供 cache_key 与最新 nodeId）
     * @param stored       存量版本记录；null 表示首次应用需 insert，非 null 走 update
     * @param clockToStore 待持久化的向量时钟（序列化为 vector_clock_json）
     */
    private void upsert(VersionedValue value, CacheVersion stored, VectorClock clockToStore) {
        if (clockToStore.snapshot().size() > syncProps.getMaxNodes()) {
            // R-03：分量数超阈值说明参与并发的节点过多，告警提示归档，防止 vector_clock_json 无限膨胀
            log.warn("[hercules-sync] vector clock for key={} has {} nodes > max {} (R-03: consider archiving)",
                    value.key(), clockToStore.snapshot().size(), syncProps.getMaxNodes());
        }
        if (stored == null) {
            CacheVersion row = new CacheVersion();
            row.setCacheKey(value.key());
            row.setNodeId(value.nodeId());
            row.setCurrentVersion(1L);
            row.setVectorClockJson(clockToStore.toJson());
            row.setUpdateTime(LocalDateTime.now());
            cacheVersionMapper.insert(row);
        } else {
            stored.setNodeId(value.nodeId());
            stored.setCurrentVersion(stored.getCurrentVersion() + 1);
            stored.setVectorClockJson(clockToStore.toJson());
            stored.setUpdateTime(LocalDateTime.now());
            cacheVersionMapper.updateById(stored);
        }
    }
}
