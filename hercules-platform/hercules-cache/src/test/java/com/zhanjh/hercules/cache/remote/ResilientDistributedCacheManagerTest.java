package com.zhanjh.hercules.cache.remote;

import com.zhanjh.hercules.cache.stats.CacheStatsCollector;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ResilientDistributedCacheManager 单元测试：验证熔断器的状态迁移与降级语义（阶段 A+ 防线②）。
 *
 * <p>测试策略：底层用可编程失败/延迟的桩实现，注入小窗口熔断器配置
 * （窗口 10 次、最少 5 次判定、失败率 50%、慢调用阈值 200ms、OPEN 300ms 自动转半开），
 * 快速验证 OPEN → 降级快速失败 → HALF_OPEN 探测 → CLOSED 的完整生命周期。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>连续失败达阈值 → 熔断 OPEN → 后续调用零阻塞降级（底层不再被调用）、get 降级返回 null；</li>
 *   <li>OPEN 持续到期 → HALF_OPEN 探测成功 → 熔断闭合，恢复后底层值可读；</li>
 *   <li>慢调用占比超阈值 → 同样触发熔断（挂起场景防护）；</li>
 *   <li>delete 失败重试 1 次后降级，不向调用方抛异常。</li>
 * </ul>
 *
 * @author zhanjh
 * @since 0.0.1
 */
class ResilientDistributedCacheManagerTest {

    /** 可编程底层桩：可设置「剩余失败次数」与「每次调用延迟」。 */
    static class StubRemote implements DistributedCacheManager {
        int getCalls;
        int deleteCalls;
        int failuresRemaining;
        long delayMs;

        private void maybeDelay() {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public String get(String key) {
            getCalls++;
            maybeDelay();
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new RuntimeException("redis boom");
            }
            return "v";
        }

        @Override
        public void put(String key, String value, Duration ttl) {
            maybeDelay();
        }

        @Override
        public void delete(String key) {
            deleteCalls++;
            maybeDelay();
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new RuntimeException("redis boom");
            }
        }
    }

    private StubRemote stub;
    private CacheStatsCollector stats;

    /**
     * 构造测试用装饰器：小窗口熔断配置（见类注释），便于毫秒级验证状态迁移。
     */
    private ResilientDistributedCacheManager decorator() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50.0f)
                .slowCallDurationThreshold(Duration.ofMillis(200))
                .slowCallRateThreshold(80.0f)
                .waitDurationInOpenState(Duration.ofMillis(300))
                .permittedNumberOfCallsInHalfOpenState(2)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        CircuitBreaker cb = CircuitBreakerRegistry.of(config).circuitBreaker("testRedisL2");
        return new ResilientDistributedCacheManager(stub, stats, null, cb);
    }

    @BeforeEach
    void setUp() {
        stub = new StubRemote();
        stats = new CacheStatsCollector();
    }

    /**
     * 验证点：连续 5 次失败（失败率 100% ≥ 50%）→ 熔断 OPEN → 后续调用零阻塞降级
     * （get 返回 null、底层不再被调用）→ OPEN 300ms 到期转 HALF_OPEN → 探测成功 → CLOSED。
     */
    @Test
    void opensAfterFailureThresholdAndRecoversThroughHalfOpen() throws Exception {
        stub.failuresRemaining = 5; // 恰好供判定窗口消耗；半开探测期已无失败，可成功闭合
        ResilientDistributedCacheManager decorator = decorator();

        // 5 次失败调用（每次都真实打到底层），达到最小判定次数且失败率 100%
        for (int i = 0; i < 5; i++) {
            assertThat(decorator.get("k")).isNull(); // 失败降级：get 返回 null
        }
        assertThat(stub.getCalls).isEqualTo(5);

        // 第 6 次调用：熔断已 OPEN，快速失败——底层不再被调用
        long start = System.nanoTime();
        assertThat(decorator.get("k")).isNull();
        assertThat(decorator.isAvailable()).isFalse();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isLessThan(50); // 零阻塞：远小于 300ms 等待期
        assertThat(stub.getCalls).isEqualTo(5);
        assertThat(stats.snapshot().redisDegraded()).isEqualTo(6);

        // OPEN 300ms 到期 → 自动转 HALF_OPEN → 探测成功 → CLOSED
        Thread.sleep(400);
        assertThat(decorator.isAvailable()).isTrue();
        assertThat(decorator.get("k")).isEqualTo("v"); // failuresRemaining 已耗尽 → 成功
        assertThat(decorator.get("k")).isEqualTo("v");
        assertThat(decorator.isAvailable()).isTrue();
        assertThat(stats.snapshot().redisCircuitState()).isEqualTo("CLOSED");
    }

    /**
     * 验证点：底层不抛异常但每次调用 250ms（> 200ms 慢调用阈值），
     * 慢调用占比 100% ≥ 80% → 熔断 OPEN（挂起场景防护）。
     */
    @Test
    void slowCallsOpenCircuit() {
        stub.delayMs = 250;
        ResilientDistributedCacheManager decorator = decorator();

        for (int i = 0; i < 5; i++) {
            assertThat(decorator.get("k")).isEqualTo("v"); // 慢但成功
        }
        assertThat(decorator.isAvailable()).isFalse(); // 慢调用率 100% → OPEN
    }

    /**
     * 验证点：delete 失败立即重试 1 次（底层被调 2 次），仍失败则降级，
     * 不向调用方抛出任何异常。
     */
    @Test
    void deleteRetriesOnceThenDegrades() {
        stub.failuresRemaining = 10;
        ResilientDistributedCacheManager decorator = decorator();

        decorator.delete("k"); // 失败 → 重试 → 再失败 → 降级

        assertThat(stub.deleteCalls).isEqualTo(2);
        assertThat(stats.snapshot().redisDegraded()).isGreaterThanOrEqualTo(1);
    }
}
