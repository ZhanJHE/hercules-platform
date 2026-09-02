package com.zhanjh.hercules.cache.strategy;

import com.zhanjh.hercules.cache.config.HerculesCacheProperties;

import java.time.Duration;

/**
 * 固定 TTL 策略实现：列表键用短 TTL（最终一致窗口），详情键用长 TTL（TTLStrategy 的 MVP 落地版本）。
 *
 * <p>核心机制：以键前缀识别列表键——前缀取 hercules.cache.list-key-prefix（默认 {@code course:list:}）；
 * 列表键 L1/L2 统一用 hercules.cache.list-ttl（默认 10s），作为「最多 10s 必拉到新列表」的
 * 最终一致窗口（列表键不纳入向量时钟版本链）；详情键 L1 用 hercules.cache.local-ttl（默认 60s）、
 * L2 用 hercules.cache.remote-ttl（默认 300s）。
 *
 * <p>线程安全性：无可变状态（props 运行期只读），可并发调用。
 *
 * <p>扩展点：自适应 TTL 可新增实现替换本 Bean，无需改动调用方。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public class FixedTTLStrategy implements TTLStrategy {

    /** 缓存配置（hercules.cache.*），提供列表键前缀与各级 TTL 数值；Spring 单例，运行期只读。 */
    private final HerculesCacheProperties props;

    /**
     * 注入配置构建固定策略。
     *
     * @param props 缓存配置，不允许为 null
     */
    public FixedTTLStrategy(HerculesCacheProperties props) {
        this.props = props;
    }

    /**
     * 判断是否为列表键：键以配置的 list-key-prefix（默认 course:list:）开头即视为列表键。
     *
     * @param key 缓存键，不允许为 null
     * @return 列表键返回 true；详情键等其他键返回 false
     */
    private boolean isListKey(String key) {
        return key.startsWith(props.getListKeyPrefix());
    }

    /**
     * 实现要点：列表键返回 list-ttl（默认 10s），其余键返回 local-ttl（默认 60s）。
     * Caffeine L1 已通过自定义 Expiry 支持逐键过期，本返回值对 L1/L2 同时生效。
     *
     * @param key 缓存键，不允许为 null
     * @return L1 过期时长（正值）
     */
    @Override
    public Duration localTtl(String key) {
        return isListKey(key) ? props.getListTtl() : props.getLocalTtl();
    }

    /**
     * 实现要点：列表键返回 list-ttl（默认 10s，最终一致窗口），其余键返回 remote-ttl（默认 300s）。
     *
     * @param key 缓存键，不允许为 null
     * @return L2 过期时长（正值）
     */
    @Override
    public Duration remoteTtl(String key) {
        return isListKey(key) ? props.getListTtl() : props.getRemoteTtl();
    }
}
