package com.zhanjh.hercules.testsupport;

import com.zhanjh.hercules.cache.remote.DistributedCacheManager;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用 L2 分布式缓存桩：以进程内 ConcurrentHashMap 模拟 Redis，让单元/冒烟测试无需外部 Redis 依赖。
 *
 * <p>实现 DistributedCacheManager 的 get/put/delete 三个命令；put 的 TTL 参数仅为满足接口签名，
 * 桩实现一律忽略（测试场景无需过期语义）。内部存储为 ConcurrentHashMap，读写天然线程安全，
 * 可安全用于多线程并发用例。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public class InMemoryDistributedCacheManager implements DistributedCacheManager {

    /** 键值存储：ConcurrentHashMap 保证并发读写的线程安全；无容量上限，条目不过期。 */
    private final Map<String, String> store = new ConcurrentHashMap<>();

    /**
     * 读取指定键的缓存值。
     *
     * @param key 缓存键
     * @return 命中时返回存储值，未命中返回 null
     */
    @Override
    public String get(String key) {
        return store.get(key);
    }

    /**
     * 写入缓存值；TTL 参数被桩实现忽略（允许传 null），值长期保留直至被覆盖或删除。
     *
     * @param key   缓存键
     * @param value 缓存值
     * @param ttl   期望存活时间（桩实现忽略，仅为满足接口签名）
     */
    @Override
    public void put(String key, String value, Duration ttl) {
        store.put(key, value);
    }

    /**
     * 删除指定键；键不存在时等同无操作。
     *
     * @param key 缓存键
     */
    @Override
    public void delete(String key) {
        store.remove(key);
    }
}
