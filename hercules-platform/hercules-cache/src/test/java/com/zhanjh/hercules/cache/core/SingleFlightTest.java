package com.zhanjh.hercules.cache.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SingleFlight 纯单元测试：验证进程内单飞的并发合并与失败语义（阶段 A+ 防线③）。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>并发合并：20 线程同键并发，loader 恰好执行 1 次，所有线程拿到相同值；</li>
 *   <li>不同键互不干扰：两个键各自执行各自的 loader；</li>
 *   <li>失败透传：队长失败 → 等待者收到同一异常，且失败不缓存——下一次调用重新执行 loader。</li>
 * </ul>
 *
 * @author zhanjh
 * @since 0.0.1
 */
class SingleFlightTest {

    private final SingleFlight singleFlight = new SingleFlight();

    /**
     * 验证点：20 线程并发同键 → loader 仅执行 1 次（其他 19 个为等待者），结果一致。
     */
    @Test
    void concurrentCallsOnSameKeyMergedIntoOneLoad() throws Exception {
        int threads = 20;
        AtomicInteger loaderCalls = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Future<String>[] futures = new Future[threads];
        for (int i = 0; i < threads; i++) {
            futures[i] = pool.submit(() -> {
                start.await();
                return singleFlight.run("course:1", () -> {
                    loaderCalls.incrementAndGet();
                    try {
                        Thread.sleep(100); // 放大并发窗口
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "v1";
                });
            });
        }
        start.countDown();
        for (Future<String> f : futures) {
            assertThat(f.get(5, TimeUnit.SECONDS)).isEqualTo("v1");
        }
        pool.shutdown();
        assertThat(loaderCalls.get()).isEqualTo(1);
    }

    /**
     * 验证点：不同键的单飞互不影响，各自执行 loader。
     */
    @Test
    void differentKeysRunIndependently() {
        String a = singleFlight.run("course:1", () -> "va");
        String b = singleFlight.run("course:2", () -> "vb");
        assertThat(a).isEqualTo("va");
        assertThat(b).isEqualTo("vb");
    }

    /**
     * 验证点：队长失败 → 异常原样透传；失败结果不缓存——下一次调用重新执行 loader。
     */
    @Test
    void leaderFailurePropagatesAndIsNotCached() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> singleFlight.run("course:3", () -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("boom");
            }
            return "ok";
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("boom");

        // 第二次调用：重新执行 loader（失败未被缓存），成功返回
        String v = singleFlight.run("course:3", () -> {
            calls.incrementAndGet();
            return "ok";
        });
        assertThat(v).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(2);
    }
}
