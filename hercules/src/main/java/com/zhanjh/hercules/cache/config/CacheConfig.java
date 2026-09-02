package com.zhanjh.hercules.cache.config;

import com.zhanjh.hercules.cache.core.MultiLevelCacheManager;
import com.zhanjh.hercules.cache.local.CaffeineLocalCacheManager;
import com.zhanjh.hercules.cache.remote.DistributedCacheManager;
import com.zhanjh.hercules.cache.stats.CacheStatsCollector;
import com.zhanjh.hercules.cache.strategy.FixedTTLStrategy;
import com.zhanjh.hercules.cache.strategy.TTLStrategy;
import com.zhanjh.hercules.sync.config.HerculesSyncProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 缓存模块装配配置：注册 L1 实现、TTL 策略、统计器与多级缓存门面四个 Bean。
 *
 * <p>装配机制：@EnableConfigurationProperties 将 HerculesCacheProperties（及同步模块的
 * HerculesSyncProperties）装配为配置属性 Bean；随后按依赖链构建——
 * CaffeineLocalCacheManager（读 hercules.cache.* 构建 Caffeine）→ FixedTTLStrategy →
 * CacheStatsCollector（存在 MeterRegistry 时立即注册 hercules.cache.* / hercules.sync.* 指标）→
 * MultiLevelCacheManager（组装 L1 + 可缺省 L2 + 策略 + 统计器）。
 *
 * <p>L2 允许缺省的原因：DistributedCacheManager 经 ObjectProvider 注入并以
 * getIfAvailable() 取值，容器中无该 Bean 时（无 Redis 依赖或单测上下文）传入 null，
 * 读路径退化为 L1 → DB，保证最小依赖下工程可启动、单测可运行。
 *
 * <p>线程安全性：四个 Bean 均为单例且无可变状态，可并发使用。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Configuration
@EnableConfigurationProperties({HerculesCacheProperties.class, HerculesSyncProperties.class})
public class CacheConfig {

    /**
     * 注册 L1 本地缓存 Bean：按配置构建 Caffeine（全局固定 TTL + 容量上限 + 统计开关）。
     *
     * @param props 缓存配置（hercules.cache.*）
     * @return Caffeine 实现的 L1 缓存管理器（单例）
     */
    @Bean
    public CaffeineLocalCacheManager caffeineLocalCacheManager(HerculesCacheProperties props) {
        return new CaffeineLocalCacheManager(props);
    }

    /**
     * 注册 TTL 策略 Bean：固定策略（列表键短 TTL、详情键长 TTL）。
     *
     * @param props 缓存配置（hercules.cache.*）
     * @return FixedTTLStrategy 实例（单例）
     */
    @Bean
    public TTLStrategy ttlStrategy(HerculesCacheProperties props) {
        return new FixedTTLStrategy(props);
    }

    /**
     * 注册统计器 Bean：存在 MeterRegistry 时立即绑定注册 hercules.cache.* / hercules.sync.* 指标。
     *
     * <p>实现要点：经 ObjectProvider&lt;MeterRegistry&gt; 拉取，getIfAvailable() 返回 null
     * （未启用 actuator 或单测环境）时跳过注册，收集器仍可正常计数，
     * 供 /api/v1/cache/stats 自定义接口读取。
     *
     * @param meterRegistry Micrometer 注册表提供者（可缺省）
     * @return 已按需绑定注册表的统计器（单例）
     */
    @Bean
    public CacheStatsCollector cacheStatsCollector(ObjectProvider<MeterRegistry> meterRegistry) {
        CacheStatsCollector collector = new CacheStatsCollector();
        MeterRegistry registry = meterRegistry.getIfAvailable();
        // 无 MeterRegistry 时跳过 Micrometer 注册（单测/无 actuator），计数功能不受影响
        if (registry != null) {
            collector.bindTo(registry);
        }
        return collector;
    }

    /**
     * 注册多级缓存门面 Bean（CacheManager 的实现）。
     *
     * <p>L2 允许缺省（ObjectProvider）：容器中无 DistributedCacheManager Bean 时
     * （无 Redis/单测环境）注入 null，读路径退化为 L1 + DB 直连，
     * 保证无 Redis 依赖时工程仍可启动运行。
     *
     * @param local       L1 本地缓存 Bean
     * @param remote      L2 分布式缓存提供者，getIfAvailable() 允许返回 null
     * @param ttlStrategy TTL 策略 Bean
     * @param stats       统计器 Bean
     * @return 多级缓存管理器（单例）
     */
    @Bean
    public MultiLevelCacheManager multiLevelCacheManager(CaffeineLocalCacheManager local,
                                                         ObjectProvider<DistributedCacheManager> remote,
                                                         TTLStrategy ttlStrategy,
                                                         CacheStatsCollector stats) {
        return new MultiLevelCacheManager(local, remote.getIfAvailable(), ttlStrategy, stats);
    }
}
