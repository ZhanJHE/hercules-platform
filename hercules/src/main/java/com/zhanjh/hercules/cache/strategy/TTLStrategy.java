package com.zhanjh.hercules.cache.strategy;

import java.time.Duration;

/**
 * TTL 策略接口（设计类：TTLStrategy，策略模式）：按缓存键决定 L1/L2 两级的过期时长。
 *
 * <p>职责：把「键 → TTL」决策从 MultiLevelCacheManager 中解耦——MVP 落地固定策略
 * {@link FixedTTLStrategy}（列表键短 TTL、详情键长 TTL）；自适应策略（按命中率/回源频次
 * 动态调整）仅预留本接口，留待后续冲刺。
 *
 * <p>契约：实现应为无状态纯函数（同一 key 返回一致结果），并返回正值 Duration——
 * 若返回 null 或非正值，L2 实现会将其视为「不过期」写入永生键。
 *
 * <p>线程安全性：实现需线程安全（可并发调用）。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public interface TTLStrategy {

    /**
     * 计算该键在 L1（本地 Caffeine）的过期时长。
     *
     * @param key 缓存键，不允许为 null
     * @return L1 过期时长（正值），如详情键默认 60s、列表键默认 10s
     */
    Duration localTtl(String key);

    /**
     * 计算该键在 L2（Redis）的过期时长。
     *
     * @param key 缓存键，不允许为 null
     * @return L2 过期时长（正值），如详情键默认 300s、列表键默认 10s
     */
    Duration remoteTtl(String key);
}
