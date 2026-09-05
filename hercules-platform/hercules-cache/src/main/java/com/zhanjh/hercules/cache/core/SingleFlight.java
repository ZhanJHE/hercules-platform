package com.zhanjh.hercules.cache.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 进程内单飞（single-flight）：同一 JVM 内对同一键的并发回源合并（阶段 A+ 防线③）。
 *
 * <p>解决的问题（缓存击穿）：L1/L2 同时未命中的瞬间，N 个并发请求会各自执行 loader
 * 全部打到 DB。本类保证「每键同一时刻只有一个队长线程执行 loader」：
 * <ul>
 *   <li>队长：putIfAbsent 抢占成功者，执行 loader 并把结果complete给所有等待者；</li>
 *   <li>等待者：阻塞在队长的 Future 上（预算 6s，覆盖「语句超时 3s + 回填」的最坏情况），
 *       拿到结果后由上层做双检/回填；</li>
 *   <li>失败不缓存：队长抛异常 → 等待者收到同一异常，键随即释放，
 *       下一个请求自动竞选新队长 —— 失败路径的并发重试是逐个放行的，不会放大击穿；</li>
 *   <li>等待超时（队长病态阻塞）：等待者降级为「自行执行 loader」（安全阀，
 *       等价于无单飞的旧行为，仅在病态场景触发，不缓存失败结果）。</li>
 * </ul>
 *
 * <p>注意：本类只做「合并」不做「缓存」，结果回填仍由 {@link MultiLevelCacheManager} 负责；
 * 跨实例的击穿由分布式回源锁（CacheLoadLock）处理，两者是两级关系。
 *
 * <p>线程安全性：in-flight 表为 ConcurrentHashMap，Future 完成语义线程安全。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public class SingleFlight {

    /** 等待者阻塞在队长 Future 上的预算：覆盖「语句超时 3s + 回填两级」的最坏情况。 */
    private static final long WAIT_BUDGET_SECONDS = 6;

    /** in-flight 表：key → 队长的 Future。 */
    private final ConcurrentHashMap<String, CompletableFuture<String>> inflight = new ConcurrentHashMap<>();

    /**
     * 单飞执行：同一键的并发调用合并为一次 loader 执行。
     *
     * @param key    单飞键（缓存键）
     * @param loader 回源逻辑（仅队长执行）
     * @return loader 结果（队长与等待者拿到相同值）
     * @throws RuntimeException loader 抛出的运行时异常原样透传给队长与所有等待者
     */
    public String run(String key, Supplier<String> loader) {
        CompletableFuture<String> myFuture = new CompletableFuture<>();
        CompletableFuture<String> leaderFuture = inflight.putIfAbsent(key, myFuture);
        if (leaderFuture == null) {
            // 我是队长：执行 loader，结果广播给等待者
            try {
                String value = loader.get();
                myFuture.complete(value);
                return value;
            } catch (RuntimeException e) {
                myFuture.completeExceptionally(e);
                throw e;
            } catch (Error e) {
                myFuture.completeExceptionally(e);
                throw e;
            } catch (Exception e) {
                // loader 声明不抛受检异常，此分支纯防御
                myFuture.completeExceptionally(e);
                throw new IllegalStateException("loader threw checked exception", e);
            } finally {
                // 结果已广播，释放键位；已持有 Future 引用的等待者不受影响
                inflight.remove(key, myFuture);
            }
        }
        // 我是等待者：阻塞等待队长结果
        try {
            return leaderFuture.get(WAIT_BUDGET_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // 队长病态阻塞（理论不该发生：loader 有 3s 语句超时）→ 安全阀：自行回源，不缓存失败
            return loader.get();
        } catch (ExecutionException e) {
            // 队长失败：异常原样透传（不缓存失败，下一个请求竞选新队长）
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("single-flight leader failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("single-flight wait interrupted", e);
        }
    }
}
