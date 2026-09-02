package com.zhanjh.hercules.cache.stats;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 缓存/同步链路运行时计数器：AtomicLong 原子累计，可绑定 Micrometer 暴露为 Prometheus 指标。
 *
 * <p>职责：缓存侧承接 MultiLevelCacheManager 读路径的 l1Hit/l1Miss/l2Hit/l2Miss/dbLoad 计数；
 * 同步侧承接 hercules.sync 消费端（VersionChangeConsumer）上报的 versionApplied/conflictDetected。
 * 双出口消费：/api/v1/cache/stats 自定义接口经 snapshot() 读取；/actuator/prometheus
 * 经 bindTo(MeterRegistry) 注册的 FunctionCounter（hercules.cache.* 与 hercules.sync.* 指标族）读取。
 *
 * <p>线程安全性：全部计数器为 AtomicLong，incrementAndGet/get 原子；snapshot() 的各计数
 * 并非同一次原子读，允许纳秒级偏差（近似一致快照）。
 *
 * <p>使用约束：不绑定 MeterRegistry（如单元测试直接 new）时实例照常可用，仅不向 Micrometer 注册。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public class CacheStatsCollector {

    /** L1（Caffeine）命中次数累计。 */
    private final AtomicLong l1Hit = new AtomicLong();
    /** L1 未命中次数累计（无论后续是否在 L2/DB 命中）。 */
    private final AtomicLong l1Miss = new AtomicLong();
    /** L2（Redis）命中次数累计（命中后回填 L1）。 */
    private final AtomicLong l2Hit = new AtomicLong();
    /** L2 未命中次数累计（remote 缺省时不产生该计数）。 */
    private final AtomicLong l2Miss = new AtomicLong();
    /** 回源 DB 成功次数累计（loader 返回 null 不计入）。 */
    private final AtomicLong dbLoad = new AtomicLong();
    /** 同步链成功应用远端版本事件的次数累计（VersionChangeConsumer 上报）。 */
    private final AtomicLong versionApplied = new AtomicLong();
    /** 同步链应用经冲突合并（向量时钟判定 CONCURRENT）处理的版本事件次数累计。 */
    private final AtomicLong conflictDetected = new AtomicLong();

    /**
     * 某一时刻的缓存运行指标快照（不可变值对象，/api/v1/cache/stats 的返回载体）。
     *
     * <p>线程安全性：Record 组件均为不可变基本类型，可安全跨线程传递。
     *
     * @param l1Hit            L1 命中次数累计
     * @param l1Miss           L1 未命中次数累计
     * @param l2Hit            L2 命中次数累计
     * @param l2Miss           L2 未命中次数累计
     * @param dbLoad           回源 DB 成功次数累计
     * @param versionApplied   同步链已应用版本事件次数
     * @param conflictDetected 同步链冲突合并次数
     * @param cacheHitRate     总体缓存命中率 = (l1Hit + l2Hit) / (l1Hit + l1Miss)，总请求数为 0 时为 0.0
     *
     * @author zhanjh
     * @since 0.0.1
     */
    public record CacheSnapshot(long l1Hit, long l1Miss, long l2Hit, long l2Miss,
                                long dbLoad, long versionApplied, long conflictDetected,
                                double cacheHitRate) {
    }

    /** L1 命中计数 +1（读路径 L1 直接命中时由 MultiLevelCacheManager 调用）。 */
    public void l1Hit() {
        l1Hit.incrementAndGet();
    }

    /** L1 未命中计数 +1（读路径未在 L1 命中、进入下探时调用）。 */
    public void l1Miss() {
        l1Miss.incrementAndGet();
    }

    /** L2 命中计数 +1（读路径 L2 命中并回填 L1 时调用）。 */
    public void l2Hit() {
        l2Hit.incrementAndGet();
    }

    /** L2 未命中计数 +1（remote 存在且未命中时调用；remote 缺省不计）。 */
    public void l2Miss() {
        l2Miss.incrementAndGet();
    }

    /** 回源 DB 成功计数 +1（loader 返回非 null 时调用）。 */
    public void dbLoad() {
        dbLoad.incrementAndGet();
    }

    /** 同步链成功应用版本事件计数 +1（hercules.sync 消费端 apply 成功后调用）。 */
    public void versionApplied() {
        versionApplied.incrementAndGet();
    }

    /** 同步链冲突合并计数 +1（向量时钟判定 CONCURRENT 并经 merge 处理后调用）。 */
    public void conflictDetected() {
        conflictDetected.incrementAndGet();
    }

    /**
     * 生成当前指标快照并计算总体缓存命中率。
     *
     * <p>命中率公式：cacheHitRate = (l1Hit + l2Hit) / (l1Hit + l1Miss)，分母为进入读路径的
     * 总请求数（L1 命中 + L1 未命中）；分母为 0（尚无读请求）时返回 0.0 避免除零，
     * 而非以 DB 回源数做分母。
     *
     * @return 包含七个计数与命中率的不可变快照
     */
    public CacheSnapshot snapshot() {
        long requests = l1Hit.get() + l1Miss.get(); // 分母 = 进入读路径的总请求数
        double hitRate = requests == 0 ? 0.0
                : (double) (l1Hit.get() + l2Hit.get()) / requests;
        return new CacheSnapshot(l1Hit.get(), l1Miss.get(), l2Hit.get(), l2Miss.get(),
                dbLoad.get(), versionApplied.get(), conflictDetected.get(), hitRate);
    }

    /**
     * 将计数器注册为 Micrometer FunctionCounter 指标（存在 MeterRegistry 时由 CacheConfig 调用一次）。
     *
     * <p>实现要点：FunctionCounter.builder(指标名, AtomicLong, AtomicLong::get) 为拉取式注册，
     * 采集时读取当前值，计数本身不经过 Micrometer。注册 7 个指标：
     * hercules.cache.l1.hit、hercules.cache.l1.miss、hercules.cache.l2.hit、hercules.cache.l2.miss、
     * hercules.cache.db.load、hercules.sync.version.applied、hercules.sync.conflict.detected。
     *
     * @param registry Micrometer 注册表（如 PrometheusMeterRegistry），不允许为 null；
     *                 无注册表场景（单测）不调用本方法，计数功能不受影响
     */
    public void bindTo(MeterRegistry registry) {
        FunctionCounter.builder("hercules.cache.l1.hit", l1Hit, AtomicLong::get).register(registry);
        FunctionCounter.builder("hercules.cache.l1.miss", l1Miss, AtomicLong::get).register(registry);
        FunctionCounter.builder("hercules.cache.l2.hit", l2Hit, AtomicLong::get).register(registry);
        FunctionCounter.builder("hercules.cache.l2.miss", l2Miss, AtomicLong::get).register(registry);
        FunctionCounter.builder("hercules.cache.db.load", dbLoad, AtomicLong::get).register(registry);
        FunctionCounter.builder("hercules.sync.version.applied", versionApplied, AtomicLong::get).register(registry);
        FunctionCounter.builder("hercules.sync.conflict.detected", conflictDetected, AtomicLong::get).register(registry);
    }
}
