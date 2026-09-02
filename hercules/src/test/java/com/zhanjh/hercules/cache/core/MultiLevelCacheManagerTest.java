package com.zhanjh.hercules.cache.core;

import com.zhanjh.hercules.cache.config.HerculesCacheProperties;
import com.zhanjh.hercules.cache.local.CaffeineLocalCacheManager;
import com.zhanjh.hercules.cache.stats.CacheStatsCollector;
import com.zhanjh.hercules.cache.strategy.FixedTTLStrategy;
import com.zhanjh.hercules.cache.strategy.TTLStrategy;
import com.zhanjh.hercules.testsupport.InMemoryDistributedCacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MultiLevelCacheManager 纯单元测试：不启动 Spring，直接装配「Caffeine L1 → 内存桩 L2 → DB loader」三级读路径，
 * 逐条验证 cache-aside 的回源、回填与失效行为。
 *
 * <p>被测对象：MultiLevelCacheManager（get 回源/逐级回填、invalidate 双级失效、invalidateLocal 仅清 L1）。
 * 测试策略：L2 用 InMemoryDistributedCacheManager 桩替代 Redis；DB 由用例内 loader 闭包模拟并用
 * AtomicInteger 计数访问次数；运行指标经 CacheStatsCollector 快照断言。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>{@code firstReadLoadsFromDbAndBackfillsBothLevels}：首次读取回源 DB 一次并回填两级，dbLoad=1；</li>
 *   <li>{@code secondReadHitsL1WithoutDbAccess}：二次读取命中 L1，DB 不再被访问（l1Miss=1、l1Hit=1）；</li>
 *   <li>{@code afterLocalEvictReadHitsL2AndBackfills}：仅失效 L1 后读取命中 L2 并回填 L1，DB 全程只访问 1 次；</li>
 *   <li>{@code afterRemoteDeleteReadFallsBackToDb}：双级皆失效后回退 DB（dbCalls=2），l2Miss 累计 2；</li>
 *   <li>{@code invalidateRemovesBothLevels}：invalidate 同时删除 L1 与 L2；</li>
 *   <li>{@code overallHitRateIsComputed}：1 次未命中 + 1 次 L1 命中 → 总体命中率 0.5。</li>
 * </ul>
 *
 * @author zhanjh
 * @since 0.0.1
 */
class MultiLevelCacheManagerTest {

    /** L1 本地缓存：真实 Caffeine 实现，按默认 HerculesCacheProperties 构造。 */
    private CaffeineLocalCacheManager local;
    /** L2 分布式缓存桩：ConcurrentHashMap 模拟 Redis，线程安全、TTL 忽略。 */
    private InMemoryDistributedCacheManager remote;
    /** TTL 策略：桩 L2 不感知 TTL，仅为满足被测对象的构造参数。 */
    private TTLStrategy ttlStrategy;
    /** 统计收集器：用例内独立计数，经 snapshot() 断言 l1Hit/l2Miss/dbLoad/命中率。 */
    private CacheStatsCollector stats;
    /** 被测对象：setUp 中每个用例重建一次，保证各用例计数互不串扰。 */
    private MultiLevelCacheManager cacheManager;

    /**
     * 每个用例前重建被测对象与依赖：L1 用真实 Caffeine、L2 用内存桩、TTL 用固定策略，
     * 全程不依赖 Spring 容器与外部中间件。
     */
    @BeforeEach
    void setUp() {
        HerculesCacheProperties props = new HerculesCacheProperties();
        local = new CaffeineLocalCacheManager(props);
        remote = new InMemoryDistributedCacheManager();
        ttlStrategy = new FixedTTLStrategy(props);
        stats = new CacheStatsCollector();
        cacheManager = new MultiLevelCacheManager(local, remote, ttlStrategy, stats);
    }

    /**
     * 验证点：L1、L2 均未命中时回源 DB 恰好一次，取到的值同时回填两级缓存，dbLoad 计 1。
     */
    @Test
    void firstReadLoadsFromDbAndBackfillsBothLevels() {
        AtomicInteger dbCalls = new AtomicInteger();
        // loader 闭包模拟 DB 回源：以 dbCalls 计数断言 DB 只被访问一次
        String v = cacheManager.get("course:1", () -> {
            dbCalls.incrementAndGet();
            return "{\"id\":1}";
        });

        assertThat(v).isEqualTo("{\"id\":1}");
        assertThat(dbCalls.get()).isEqualTo(1);
        assertThat(local.get("course:1")).isNotNull();
        assertThat(remote.get("course:1")).isEqualTo("{\"id\":1}");
        assertThat(stats.snapshot().dbLoad()).isEqualTo(1);
    }

    /**
     * 验证点：同一键第二次读取直接命中 L1、不再回源——dbCalls 保持 1，
     * 统计 l1Miss=1（首次未命中）、l1Hit=1（二次命中）。
     */
    @Test
    void secondReadHitsL1WithoutDbAccess() {
        AtomicInteger dbCalls = new AtomicInteger();
        cacheManager.get("course:1", countingLoader(dbCalls));
        cacheManager.get("course:1", countingLoader(dbCalls));

        assertThat(dbCalls.get()).isEqualTo(1);
        assertThat(stats.snapshot().l1Hit()).isEqualTo(1);
        assertThat(stats.snapshot().l1Miss()).isEqualTo(1);
    }

    /**
     * 验证点：仅失效 L1 后再次读取命中 L2 并回填 L1——DB 全程只被访问 1 次，
     * 统计 l2Hit=1，且 local 中重新可见该键。
     */
    @Test
    void afterLocalEvictReadHitsL2AndBackfills() {
        AtomicInteger dbCalls = new AtomicInteger();
        cacheManager.get("course:1", countingLoader(dbCalls));
        // 只清 L1、保留 L2：下次读取应从 L2 恢复而非回源 DB
        cacheManager.invalidateLocal("course:1");

        String v = cacheManager.get("course:1", countingLoader(dbCalls));

        assertThat(v).isEqualTo("{\"id\":1}");
        assertThat(dbCalls.get()).isEqualTo(1);
        assertThat(stats.snapshot().l2Hit()).isEqualTo(1);
        assertThat(local.get("course:1")).isNotNull();
    }

    /**
     * 验证点：L1、L2 双双失效后读取回退 DB——dbCalls 达 2；
     * l2Miss=2 是因为首次读取（彼时 L2 为空）也已计过一次未命中。
     */
    @Test
    void afterRemoteDeleteReadFallsBackToDb() {
        AtomicInteger dbCalls = new AtomicInteger();
        cacheManager.get("course:1", countingLoader(dbCalls));   // 首次读取：L1/L2 均未命中 → 回源 DB（第 1 次 l2Miss）
        remote.delete("course:1");
        local.invalidate("course:1");

        cacheManager.get("course:1", countingLoader(dbCalls));   // 双级已失效：L1/L2 再次未命中 → 回退 DB（第 2 次 l2Miss）

        assertThat(dbCalls.get()).isEqualTo(2);
        assertThat(stats.snapshot().l2Miss()).isEqualTo(2);
    }

    /**
     * 验证点：{@code invalidate} 同步删除 L1 与 L2，随后对两级的直接读取均返回 null。
     */
    @Test
    void invalidateRemovesBothLevels() {
        cacheManager.get("course:1", () -> "{\"id\":1}");
        cacheManager.invalidate("course:1");

        assertThat(local.get("course:1")).isNull();
        assertThat(remote.get("course:1")).isNull();
    }

    /**
     * 验证点：总体命中率 =（L1 命中 + L2 命中）/ 总读次数——
     * 1 次未命中 + 1 次 L1 命中 → 命中率恰为 0.5。
     */
    @Test
    void overallHitRateIsComputed() {
        cacheManager.get("course:1", () -> "{\"id\":1}");   // 第 1 次读取：未命中（回源 DB）
        cacheManager.get("course:1", () -> "{\"id\":1}");   // 第 2 次读取：命中 L1
        assertThat(stats.snapshot().cacheHitRate()).isEqualTo(0.5);
    }

    /**
     * 构造带 DB 访问计数的 loader 闭包：每次被调用计数 +1，并返回固定值模拟 DB 回源。
     *
     * @param counter 用例内创建的累加计数器（单线程访问）
     * @return 恒定返回 {@code "{\"id\":1}"} 的 Supplier，替代真实 DB 查询
     */
    private java.util.function.Supplier<String> countingLoader(AtomicInteger counter) {
        return () -> {
            counter.incrementAndGet();
            return "{\"id\":1}";
        };
    }
}
