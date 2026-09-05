package com.zhanjh.hercules.cache.config;

import com.zhanjh.hercules.cache.core.MultiLevelCacheManager;
import com.zhanjh.hercules.cache.core.SingleFlight;
import com.zhanjh.hercules.cache.local.CaffeineLocalCacheManager;
import com.zhanjh.hercules.cache.remote.CacheLoadLock;
import com.zhanjh.hercules.cache.remote.DistributedCacheManager;
import com.zhanjh.hercules.cache.remote.NoopCacheLoadLock;
import com.zhanjh.hercules.cache.remote.NoopDistributedCacheManager;
import com.zhanjh.hercules.cache.remote.RedisCacheLoadLock;
import com.zhanjh.hercules.cache.remote.RedisDistributedCacheManager;
import com.zhanjh.hercules.cache.remote.ResilientDistributedCacheManager;
import com.zhanjh.hercules.cache.stats.CacheStatsCollector;
import com.zhanjh.hercules.cache.strategy.FixedTTLStrategy;
import com.zhanjh.hercules.cache.strategy.TTLStrategy;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 缓存模块装配配置：注册 L1 实现、TTL 策略、统计器与多级缓存门面四个 Bean。
 *
 * <p>装配机制：@EnableConfigurationProperties 将 HerculesCacheProperties 装配为配置属性 Bean
 * （同步模块的 HerculesSyncProperties 由 hercules-sync 的 SyncConfig 自行注册，模块间不交叉）；
 * 随后按依赖链构建——
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
@EnableConfigurationProperties(HerculesCacheProperties.class)
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
     * 注册 L2 分布式缓存 Bean（阶段 A+ 高可用加固）。
     *
     * <p>装配策略：无 StringRedisTemplate（无 Redis 依赖/单测上下文）时注册
     * {@link NoopDistributedCacheManager}（L2 恒未命中、isAvailable=false，读路径退化为 L1 + DB）；
     * 有 Redis 时按开关包装为 {@link ResilientDistributedCacheManager} 熔断装饰器
     * （命令超时 + 失败率/慢调用率熔断 + 降级），装饰器内部委托 Redis 实现。
     * 经 ObjectProvider 延迟解析 StringRedisTemplate，避免用户配置类与自动配置的注册顺序问题。
     *
     * @param redisTemplate Redis 模板提供者（可缺省）
     * @param props         缓存配置（熔断开关等）
     * @param stats         统计器（降级计数/熔断状态展示）
     * @param meterRegistry Micrometer 注册表提供者（可缺省，用于注册 resilience4j 指标）
     * @return L2 分布式缓存实现（单例；生产为熔断装饰器，无 Redis 为 Noop）
     */
    @Bean
    public DistributedCacheManager distributedCacheManager(ObjectProvider<StringRedisTemplate> redisTemplate,
                                                           HerculesCacheProperties props,
                                                           CacheStatsCollector stats,
                                                           ObjectProvider<MeterRegistry> meterRegistry) {
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        if (template == null) {
            return new NoopDistributedCacheManager();
        }
        RedisDistributedCacheManager redis = new RedisDistributedCacheManager(template);
        if (!props.isCircuitBreakerEnabled()) {
            return redis;
        }
        return new ResilientDistributedCacheManager(redis, stats, meterRegistry.getIfAvailable());
    }

    /**
     * 注册进程内单飞 Bean（阶段 A+ 防线③第一级：同键并发回源合并）。
     *
     * @return 单飞组件（单例，无状态）
     */
    @Bean
    public SingleFlight singleFlight() {
        return new SingleFlight();
    }

    /**
     * 注册分布式回源锁 Bean（阶段 A+ 防线③第二级：跨实例回源合并）。
     *
     * <p>装配策略：开关关闭或无 StringRedisTemplate 时注册 {@link NoopCacheLoadLock}
     * （tryLock 恒 true，等价于无分布式锁的单机行为）；否则为 Redis 实现
     * （SET NX PX 抢锁 + Lua 比对令牌释放）。
     *
     * @param redisTemplate Redis 模板提供者（可缺省）
     * @param props         缓存配置（锁开关）
     * @return 回源锁实现（单例）
     */
    @Bean
    public CacheLoadLock cacheLoadLock(ObjectProvider<StringRedisTemplate> redisTemplate,
                                       HerculesCacheProperties props) {
        if (!props.isLoadLockEnabled()) {
            return new NoopCacheLoadLock();
        }
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        return template == null ? new NoopCacheLoadLock() : new RedisCacheLoadLock(template);
    }

    /**
     * 注册多级缓存门面 Bean（CacheManager 的实现）。
     *
     * <p>L2 允许缺省（ObjectProvider）：容器中无 DistributedCacheManager Bean 时
     * （极端装配场景）注入 null，读路径退化为 L1 + DB 直连；
     * 正常装配下本 Bean 由上方 distributedCacheManager 提供（熔断装饰器或 Noop）。
     *
     * @param local        L1 本地缓存 Bean
     * @param remote       L2 分布式缓存提供者，getIfAvailable() 允许返回 null
     * @param ttlStrategy  TTL 策略 Bean
     * @param stats        统计器 Bean
     * @param singleFlight 进程内单飞 Bean
     * @param loadLock     分布式回源锁 Bean
     * @param props        缓存配置 Bean
     * @return 多级缓存管理器（单例）
     */
    @Bean
    public MultiLevelCacheManager multiLevelCacheManager(CaffeineLocalCacheManager local,
                                                         ObjectProvider<DistributedCacheManager> remote,
                                                         TTLStrategy ttlStrategy,
                                                         CacheStatsCollector stats,
                                                         SingleFlight singleFlight,
                                                         CacheLoadLock loadLock,
                                                         HerculesCacheProperties props) {
        return new MultiLevelCacheManager(local, remote.getIfAvailable(), ttlStrategy, stats,
                singleFlight, loadLock, props);
    }
}
