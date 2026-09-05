package com.zhanjh.hercules.cache.remote;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import com.zhanjh.hercules.cache.stats.CacheStatsCollector;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 弹性装饰器：为任意 {@link DistributedCacheManager} 套上 Resilience4j 熔断器（阶段 A+ 防线②）。
 *
 * <p>解决的问题：Redis 挂起/网络抖动时，若 L2 调用无界阻塞，Tomcat 工作线程会被
 * 逐一拖住直至线程池耗尽。本装饰器提供两重保护：
 * <ul>
 *   <li>配合命令超时（hercules 层面 200ms），每次失败/慢调用的代价有界；</li>
 *   <li>失败率/慢调用率超阈值后熔断器转 OPEN，后续调用<b>零阻塞快速失败</b>并降级。</li>
 * </ul>
 *
 * <p>降级语义（不向调用方抛错，数据正确性不受影响）：
 * <ul>
 *   <li>get → 返回 null（等价未命中）：读路径自然落到 DB 回源，数据仍正确；</li>
 *   <li>put → 跳过：下次读经 loader 回源自愈；</li>
 *   <li>delete → 立即重试 1 次，仍失败则跳过：Redis 故障期间失效操作本就无意义
 *       （读路径同时降级直连 DB）；恢复后残留旧值最多存活到剩余 TTL（≤300s），
 *       且下一次版本应用会覆盖 —— 已知取舍，记录于工作日志。</li>
 * </ul>
 *
 * <p>熔断参数（代码常量，未开放配置以避免参数表膨胀）：滑动窗口 50 次、最少 10 次判定、
 * 失败率阈值 50%、慢调用阈值 300ms（对齐命令超时）、慢调用占比 80%、OPEN 持续 10s 自动转
 * HALF_OPEN、半开放行 5 次探测。
 *
 * <p>可观测：接入 MeterRegistry 时注册状态 gauge（hercules_cache_redis_circuit_state：
 * 0=CLOSED 1=HALF_OPEN 2=OPEN），并把当前状态采样同步到 {@link CacheStatsCollector}
 * 供 /api/v1/cache/stats 展示；降级次数经 stats.redisDegraded 暴露为
 * hercules.cache.redis.degraded 计数器（/actuator/prometheus）。
 *
 * <p>线程安全性：delegate/CircuitBreaker/统计器均线程安全，可并发调用。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public class ResilientDistributedCacheManager implements DistributedCacheManager {

    /** 被装饰的真实 L2 实现（如 RedisDistributedCacheManager）。 */
    private final DistributedCacheManager delegate;

    /** 熔断器实例（名为 redisL2）。 */
    private final CircuitBreaker circuitBreaker;

    /** 统计器：降级计数与熔断状态展示。 */
    private final CacheStatsCollector stats;

    /**
     * 构建装饰器：使用默认熔断参数（见类注释），由 CacheConfig 调用。
     *
     * @param delegate     真实 L2 实现，不允许为 null
     * @param stats        统计器，不允许为 null
     * @param meterRegistry Micrometer 注册表，可为 null（单测/未启用 actuator 时跳过指标注册）
     */
    public ResilientDistributedCacheManager(DistributedCacheManager delegate,
                                            CacheStatsCollector stats,
                                            MeterRegistry meterRegistry) {
        this(delegate, stats, meterRegistry, CircuitBreakerRegistry.of(defaultConfig()).circuitBreaker("redisL2"));
    }

    /**
     * 构建装饰器（测试用）：注入预配置的熔断器，便于用小窗口参数快速验证状态迁移。
     * 包内可见，生产装配走上方公开构造器。
     *
     * @param delegate       真实 L2 实现，不允许为 null
     * @param stats          统计器，不允许为 null
     * @param meterRegistry  Micrometer 注册表，可为 null
     * @param circuitBreaker 预配置熔断器，不允许为 null
     */
    ResilientDistributedCacheManager(DistributedCacheManager delegate,
                                     CacheStatsCollector stats,
                                     MeterRegistry meterRegistry,
                                     CircuitBreaker circuitBreaker) {
        this.delegate = delegate;
        this.stats = stats;
        this.circuitBreaker = circuitBreaker;
        if (meterRegistry != null) {
            MeterRegistry registry = meterRegistry;
            io.micrometer.core.instrument.Gauge.builder("hercules_cache_redis_circuit_state",
                            this.circuitBreaker,
                            cb -> switch (cb.getState()) {
                                case OPEN -> 2;
                                case HALF_OPEN -> 1;
                                default -> 0;
                            })
                    .description("Redis L2 circuit breaker state: 0=CLOSED 1=HALF_OPEN 2=OPEN")
                    .register(registry);
        }
    }

    /**
     * 熔断器默认参数（含义见类注释）。
     *
     * @return 熔断器配置
     */
    private static CircuitBreakerConfig defaultConfig() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(50)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50.0f)
                .slowCallDurationThreshold(Duration.ofMillis(300))
                .slowCallRateThreshold(80.0f)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(5)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
    }

    /**
     * 实现：熔断器 OPEN 时直接降级返回 null（不触发底层调用）；
     * 底层异常/超时同样降级为 null（读路径语义 = 未命中 → 落到 DB 回源）。
     */
    @Override
    public String get(String key) {
        return protect(() -> delegate.get(key));
    }

    /**
     * 实现：降级时跳过写入（缓存缺失由读路径回源自愈）。
     */
    @Override
    public void put(String key, String value, Duration ttl) {
        protect(() -> {
            delegate.put(key, value, ttl);
            return null;
        });
    }

    /**
     * 实现：删除失败立即重试 1 次（失效操作对一致性影响最大，值得多试一次），
     * 仍失败则降级跳过 —— 残留旧值的存活上界为剩余 TTL（见类注释「降级语义」）。
     * 重试同样计入熔断窗口（连续失败会加速熔断，属预期行为）。
     */
    @Override
    public void delete(String key) {
        try {
            circuitBreaker.executeSupplier(() -> {
                delegate.delete(key);
                return null;
            });
            return;
        } catch (Exception firstAttempt) {
            // 第一次失败：立即重试一次（失效失败可能残留旧值，比对 put 更值得重试）
            try {
                circuitBreaker.executeSupplier(() -> {
                    delegate.delete(key);
                    return null;
                });
                return;
            } catch (Exception secondAttempt) {
                stats.redisDegraded();
            }
        }
    }

    /**
     * 实现：OPEN 期间不可用（读路径据此跳过 L2 双检、分布式回源锁与等待轮询）；
     * HALF_OPEN 放行探测请求，视为可用。
     */
    @Override
    public boolean isAvailable() {
        return circuitBreaker.getState() != CircuitBreaker.State.OPEN;
    }

    /**
     * 统一保护模板：经熔断器执行底层操作，任何失败/慢调用被统计后降级。
     * 每次调用后把熔断器状态采样到统计器（无事件监听 API，采样式同步足够）。
     *
     * @param op 底层操作
     * @param <T> 返回类型
     * @return 操作结果；降级时返回 null（get 语义 = 未命中）
     */
    private <T> T protect(Supplier<T> op) {
        try {
            return circuitBreaker.executeSupplier(op);
        } catch (Exception e) {
            // 含 CallNotPermittedException（OPEN 快速失败）与底层 Redis 异常/命令超时
            stats.redisDegraded();
            return null;
        } finally {
            stats.redisCircuitState(circuitBreaker.getState().name());
        }
    }
}
