package com.zhanjh.hercules.cache.core;

import java.util.function.Supplier;

/**
 * 多级缓存门面接口（设计类：CacheManager），对业务层屏蔽 L1/L2/DB 细节，缓存值统一以 JSON 字符串承载。
 *
 * <p>职责与机制：本接口是 FR-HC-01 多级缓存体系的顶层抽象，业务代码只依赖它；
 * 由 {@link MultiLevelCacheManager} 以门面模式实现，内部编排 L1（Caffeine 进程内）
 * → L2（Redis 分布式）→ loader（MySQL）的 cache-aside 读路径并逐级回填。
 *
 * <p>线程安全性：实现类基于 Caffeine 与 Lettuce 的线程安全原语，接口本身不引入状态，
 * 允许多线程并发调用；但不保证 invalidate 后其他节点立即可见
 * （跨节点 L1 失效由 hercules.sync 同步链异步广播）。
 *
 * <p>扩展点：新增实现（如引入 Redisson 的分布式锁版本）实现本接口替换即可，业务代码无需改动。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public interface CacheManager {

    /**
     * cache-aside 读取契约：L1 → L2 → loader(DB) 逐级下探，命中逐级回填。
     *
     * <p>契约细节：L2 命中按 TTL 策略回填 L1；loader 命中依次回填 L2 与 L1；
     * loader 返回 null 表示数据不存在——不计数、不回填、不缓存空值
     * （MVP 不做穿透保护，由业务保证只查询有效键）。
     *
     * @param key    缓存键，如详情键 {@code course:{id}} 或列表键 {@code course:list:{p}:{s}:{kw}}，
     *               不允许为 null
     * @param loader L1/L2 均未命中时的回源函数（通常查 MySQL），返回序列化后的 JSON 字符串；
     *               允许返回 null 表示数据库无数据
     * @return 最先命中一层级的 JSON 字符串值；L1/L2/DB 均无数据时返回 null
     */
    String get(String key, Supplier<String> loader);

    /**
     * 写入契约：值写入 L2（带 TTL）与本节点 L1，本进程后续读取即可命中。
     *
     * @param key   缓存键，不允许为 null
     * @param value JSON 字符串形式的缓存值，不允许为 null
     */
    void put(String key, String value);

    /**
     * 失效契约：同时删除 L2 与本节点 L1，用于数据变更后的主动清除；
     * 其他节点的 L1 失效由 hercules.sync 同步链广播完成。
     *
     * @param key 待失效的缓存键，不允许为 null
     */
    void invalidate(String key);

    /**
     * 仅失效本节点 L1 契约（L2 保留）：下次读取将从 L2 命中并回填 L1，用于演示「Redis 回填」场景。
     *
     * @param key 待失效的缓存键，不允许为 null
     */
    void invalidateLocal(String key);
}
