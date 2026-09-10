package com.zhanjh.hercules.sync.consumer;

import com.zhanjh.hercules.cache.config.HerculesCacheProperties;
import com.zhanjh.hercules.cache.local.CaffeineLocalCacheManager;
import com.zhanjh.hercules.cache.stats.CacheStatsCollector;
import com.zhanjh.hercules.cache.strategy.FixedTTLStrategy;
import com.zhanjh.hercules.mapper.CacheVersionMapper;
import com.zhanjh.hercules.model.CacheVersion;
import com.zhanjh.hercules.sync.clock.VectorClock;
import com.zhanjh.hercules.sync.config.HerculesSyncProperties;
import com.zhanjh.hercules.sync.model.VersionedValue;
import com.zhanjh.hercules.sync.resolver.FieldLwwMergeStrategy;
import com.zhanjh.hercules.testsupport.InMemoryDistributedCacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VersionChangeConsumer 纯单元测试：以 Mockito 桩替代 CacheVersionMapper（不连数据库），
 * 验证版本变更消息按向量时钟关系分流处理——应用、丢弃、或经字段级 LWW 合并后应用。
 *
 * <p>被测对象：VersionChangeConsumer.onMessage 的三分支裁决（AFTER 应用并 upsert 版本行、
 * EQUAL/BEFORE 幂等丢弃、CONCURRENT 字段级 LWW 合并）。测试策略：L2 用内存桩、L1 用真实 Caffeine、
 * 合并用真实 FieldLwwMergeStrategy，存量行由 Mockito 桩的 selectOne 返回。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>{@code noStoredVersionAppliesAsNew}：无存量版本 → 按 AFTER 应用：写 L2、insert 版本行、versionApplied=1、conflictDetected=0；</li>
 *   <li>{@code dominatedVersionIsDiscarded}：来包被存量支配 → 丢弃：不写 L2、不 insert/不更新版本行、versionApplied=0；</li>
 *   <li>{@code dominatingVersionIsApplied}：来包支配存量 → 乐观锁更新（expectedVersion=4）且时钟 JSON 含新增节点 node-2；</li>
 *   <li>{@code concurrentVersionIsMergedWithLww}：并发冲突 → 字段级 LWW 合并（courseName 取新值、teacherName 保留）、时钟取并集（node-1+node-sim）、conflictDetected=1；</li>
 *   <li>{@code mergedValueAlsoInvalidatesLocalCache}：合并应用后同步失效 L1，本地 stale-value 被清除；</li>
 *   <li>{@code insertDuplicateKeyFallsBackToOptimisticUpdate}（遗留事项清理）：多实例并发首写，insert 唯一键冲突 → 重查转乐观锁更新；</li>
 *   <li>{@code optimisticLockConflictRetriesOnceThenSkipsPersist}（遗留事项清理）：乐观锁两次竞争失败 → 缓存已应用、时钟持久化跳过、不抛异常。</li>
 * </ul>
 *
 * @author zhanjh
 * @since 0.0.1
 */
@ExtendWith(MockitoExtension.class)
class VersionChangeConsumerTest {

    /** 测试固定的缓存键：与 CacheVersion.cache_key 及 L1/L2 缓存键保持一致。 */
    private static final String KEY = "course:1";

    /** Mockito 桩：替代 MyBatis-Plus Mapper，模拟 t_cache_version 的存量行查询，不依赖真实数据库。 */
    @Mock
    private CacheVersionMapper cacheVersionMapper;

    /** L2 分布式缓存桩：ConcurrentHashMap 模拟 Redis，线程安全、TTL 忽略，用于断言写入与合并结果。 */
    private InMemoryDistributedCacheManager remote;
    /** L1 本地缓存：真实 Caffeine 实现，用于断言消费端应用版本后的失效行为。 */
    private CaffeineLocalCacheManager local;
    /** 统计收集器：断言 versionApplied/conflictDetected 计数。 */
    private CacheStatsCollector stats;
    /** 被测对象：setUp 中手工装配，Mapper 注入 Mockito 桩，其余为真实/桩实现。 */
    private VersionChangeConsumer consumer;

    /**
     * 手工装配被测对象：Mapper 用 Mockito 桩、L2 用内存桩、L1/合并策略/统计为真实实现，不启动 Spring。
     */
    @BeforeEach
    void setUp() {
        HerculesCacheProperties cacheProps = new HerculesCacheProperties();
        remote = new InMemoryDistributedCacheManager();
        local = new CaffeineLocalCacheManager(cacheProps);
        stats = new CacheStatsCollector();
        consumer = new VersionChangeConsumer(
                cacheVersionMapper,
                remote,
                local,
                new FieldLwwMergeStrategy(),
                new FixedTTLStrategy(cacheProps),
                stats,
                new HerculesSyncProperties());
    }

    /**
     * 构造一条来自 node-1 的版本变更消息。
     *
     * @param valueJson 消息携带的缓存值 JSON
     * @param clock     消息携带的向量时钟
     * @param ts        消息时间戳（毫秒，字段级 LWW 裁决中的「新值时间」）
     * @return 以 {@code course:1} 为键、nodeId=node-1 的 VersionedValue
     */
    private VersionedValue incoming(String valueJson, VectorClock clock, long ts) {
        return new VersionedValue(KEY, valueJson, "node-1", ts, clock);
    }

    /**
     * 验证点：无存量版本时消息按 AFTER 应用——值写入 L2、版本行走 insert（而非 updateById）、
     * versionApplied=1、conflictDetected=0（无冲突）。
     */
    @Test
    void noStoredVersionAppliesAsNew() {
        when(cacheVersionMapper.selectOne(any())).thenReturn(null);

        // 存量为空 → 空时钟被来包支配（AFTER）→ 走应用分支
        consumer.onMessage(incoming("{\"id\":1,\"enrolled\":88}",
                new VectorClock().increment("node-1"), 1000L));

        assertThat(remote.get(KEY)).isEqualTo("{\"id\":1,\"enrolled\":88}");
        verify(cacheVersionMapper).insert(any(CacheVersion.class));
        verify(cacheVersionMapper, never()).updateVersionRow(any(), any(), any(), any(), any());
        assertThat(stats.snapshot().versionApplied()).isEqualTo(1);
        assertThat(stats.snapshot().conflictDetected()).isZero();
    }

    /**
     * 验证点：来包时钟（node-1:3）被存量（node-1:5）支配（BEFORE）→ 整条消息按幂等语义丢弃：
     * L2 无写入、版本行不 insert 也不 updateById、versionApplied 保持 0。
     */
    @Test
    void dominatedVersionIsDiscarded() {
        CacheVersion stored = storedRow(5L, new VectorClock(Map.of("node-1", 5L)));
        when(cacheVersionMapper.selectOne(any())).thenReturn(stored);

        consumer.onMessage(incoming("{\"id\":1,\"enrolled\":3}",
                new VectorClock(Map.of("node-1", 3L)), 1000L));

        assertThat(remote.get(KEY)).isNull();
        verify(cacheVersionMapper, never()).insert(any(CacheVersion.class));
        verify(cacheVersionMapper, never()).updateVersionRow(any(), any(), any(), any(), any());
        assertThat(stats.snapshot().versionApplied()).isZero();
    }

    /**
     * 验证点：来包时钟（node-1:5 + 新增 node-2:1）支配存量（node-1:3）→ 应用并 upsert：
     * 值写入 L2；乐观锁更新以 expectedVersion=4 提交（updateVersionRow），时钟 JSON 含新增节点 node-2。
     */
    @Test
    void dominatingVersionIsApplied() {
        CacheVersion stored = storedRow(4L, new VectorClock(Map.of("node-1", 3L)));
        when(cacheVersionMapper.selectOne(any())).thenReturn(stored);
        when(cacheVersionMapper.updateVersionRow(any(), any(), any(), any(), any())).thenReturn(1);

        consumer.onMessage(incoming("{\"id\":1,\"enrolled\":90}",
                new VectorClock(Map.of("node-1", 5L, "node-2", 1L)), 2000L));

        assertThat(remote.get(KEY)).isEqualTo("{\"id\":1,\"enrolled\":90}");
        ArgumentCaptor<String> clockCaptor = ArgumentCaptor.forClass(String.class);
        verify(cacheVersionMapper).updateVersionRow(eq(1L), eq(4L), eq("node-1"), clockCaptor.capture(), any());
        assertThat(clockCaptor.getValue()).contains("node-2");
    }

    /**
     * 验证点：来包（node-sim）与存量（时钟 node-1:3）互不支配 → CONCURRENT → 字段级 LWW 合并：
     * 冲突字段 courseName 因消息时间戳更新取「程序设计基础(合并后)」，仅存量持有的 teacherName「张伟」保留；
     * 时钟取并集（同时含 node-1 与 node-sim），conflictDetected=1、versionApplied=1。
     */
    @Test
    void concurrentVersionIsMergedWithLww() {
        CacheVersion stored = storedRow(2L, new VectorClock(Map.of("node-1", 3L)));
        // 存量 updateTime 是合并时「旧值时间戳」的来源
        stored.setUpdateTime(LocalDateTime.now());
        when(cacheVersionMapper.selectOne(any())).thenReturn(stored);
        when(cacheVersionMapper.updateVersionRow(any(), any(), any(), any(), any())).thenReturn(1);
        // 预置 L2 现值：courseName 与 teacherName 并存，合并后 teacherName 应保留
        remote.put(KEY, "{\"id\":1,\"courseName\":\"程序设计基础\",\"teacherName\":\"张伟\"}", null);

        // 消息时间戳取调用时刻、晚于存量 updateTime → 冲突字段由新值获胜
        long now = System.currentTimeMillis();
        consumer.onMessage(new VersionedValue(KEY, "{\"id\":1,\"courseName\":\"程序设计基础(合并后)\"}",
                "node-sim", now, new VectorClock().increment("node-sim")));

        String merged = remote.get(KEY);
        assertThat(merged).contains("程序设计基础(合并后)").contains("张伟");
        ArgumentCaptor<String> clockCaptor = ArgumentCaptor.forClass(String.class);
        verify(cacheVersionMapper).updateVersionRow(eq(1L), eq(2L), eq("node-sim"), clockCaptor.capture(), any());
        assertThat(clockCaptor.getValue())
                .contains("node-1")
                .contains("node-sim");
        assertThat(stats.snapshot().conflictDetected()).isEqualTo(1);
        assertThat(stats.snapshot().versionApplied()).isEqualTo(1);
    }

    /**
     * 验证点：并发消息合并应用的同时失效 L1——预置的本地 stale-value 在处理后不可再读。
     */
    @Test
    void mergedValueAlsoInvalidatesLocalCache() {
        CacheVersion stored = storedRow(1L, new VectorClock(Map.of("node-1", 1L)));
        stored.setUpdateTime(LocalDateTime.now());
        when(cacheVersionMapper.selectOne(any())).thenReturn(stored);
        when(cacheVersionMapper.updateVersionRow(any(), any(), any(), any(), any())).thenReturn(1);
        // 预置过期 L1 值，验证消费端应用版本后必定失效本地缓存
        local.put(KEY, "stale-value", null);
        remote.put(KEY, "{\"id\":1,\"courseName\":\"旧\"}", null);

        consumer.onMessage(new VersionedValue(KEY, "{\"id\":1,\"courseName\":\"新\"}",
                "node-sim", System.currentTimeMillis(), new VectorClock().increment("node-sim")));

        assertThat(local.get(KEY)).isNull();
    }

    /**
     * 验证点（遗留事项清理，多实例并发首写）：insert 命中 uk_cache_key 唯一键冲突 →
     * 重查存量行转乐观锁更新，最终版本行被更新且消息正常应用（不抛异常、计数正常）。
     */
    @Test
    void insertDuplicateKeyFallsBackToOptimisticUpdate() {
        CacheVersion existing = storedRow(3L, new VectorClock(Map.of("node-2", 2L)));
        // 首次 selectOne（决策读）无存量；冲突后 loadByKey 重查返回并发方已建的行
        when(cacheVersionMapper.selectOne(any())).thenReturn(null, existing);
        when(cacheVersionMapper.insert(any(CacheVersion.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry 'course:1' for key 'uk_cache_key'"));
        when(cacheVersionMapper.updateVersionRow(any(), any(), any(), any(), any())).thenReturn(1);

        consumer.onMessage(incoming("{\"id\":1,\"enrolled\":88}",
                new VectorClock().increment("node-1"), 1000L));

        assertThat(remote.get(KEY)).isEqualTo("{\"id\":1,\"enrolled\":88}");
        verify(cacheVersionMapper).insert(any(CacheVersion.class));
        verify(cacheVersionMapper).updateVersionRow(eq(1L), eq(3L), eq("node-1"), any(), any());
        assertThat(stats.snapshot().versionApplied()).isEqualTo(1);
    }

    /**
     * 验证点（遗留事项清理，乐观锁竞争失败）：两次 updateVersionRow 均 0 行（并发方持续推进）→
     * 重查重试一次后放弃时钟持久化并记 warn——缓存值已应用、不抛异常，下一条消息会重新决策。
     */
    @Test
    void optimisticLockConflictRetriesOnceThenSkipsPersist() {
        CacheVersion stored = storedRow(4L, new VectorClock(Map.of("node-1", 3L)));
        when(cacheVersionMapper.selectOne(any())).thenReturn(stored);
        when(cacheVersionMapper.updateVersionRow(any(), any(), any(), any(), any())).thenReturn(0, 0);

        consumer.onMessage(incoming("{\"id\":1,\"enrolled\":90}",
                new VectorClock(Map.of("node-1", 5L, "node-2", 1L)), 2000L));

        assertThat(remote.get(KEY)).isEqualTo("{\"id\":1,\"enrolled\":90}");
        verify(cacheVersionMapper, times(2)).updateVersionRow(any(), any(), any(), any(), any());
        assertThat(stats.snapshot().versionApplied()).isEqualTo(1);
    }

    /**
     * 构造一条 t_cache_version 存量行，模拟 cacheVersionMapper.selectOne 的返回。
     *
     * @param currentVersion 存量当前版本号
     * @param clock          存量向量时钟（toJson 后存入 vectorClockJson）
     * @return id=1、cacheKey=course:1、nodeId=node-1、updateTime=当前时间的 CacheVersion 行
     */
    private CacheVersion storedRow(long currentVersion, VectorClock clock) {
        CacheVersion row = new CacheVersion();
        row.setId(1L);
        row.setCacheKey(KEY);
        row.setNodeId("node-1");
        row.setCurrentVersion(currentVersion);
        row.setVectorClockJson(clock.toJson());
        row.setUpdateTime(LocalDateTime.now());
        return row;
    }
}
