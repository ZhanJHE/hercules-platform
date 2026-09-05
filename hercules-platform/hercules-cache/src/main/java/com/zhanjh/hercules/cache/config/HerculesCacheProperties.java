package com.zhanjh.hercules.cache.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 缓存模块配置属性类（hercules.cache 前缀）：集中定义 L1/L2 TTL、容量上限与列表键识别规则。
 *
 * <p>装配机制：由 {@link CacheConfig} 的 @EnableConfigurationProperties 装配为 Spring 单例；
 * 每个字段对应 application.yml 中 hercules.cache.* 的一个键（键名见各字段注释），
 * Duration 字段支持 {@code 60s}/{@code 5m} 等 Spring Boot Duration 绑定写法。
 *
 * <p>消费方：CaffeineLocalCacheManager 读 localTtl/localMaxSize 构建 Caffeine；
 * FixedTTLStrategy 读 remoteTtl/listTtl/listKeyPrefix 做 TTL 决策。
 *
 * <p>线程安全性：Spring 绑定完成后运行期只读。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@ConfigurationProperties(prefix = "hercules.cache")
public class HerculesCacheProperties {

    /** Caffeine L1 固定 TTL（expireAfterWrite，所有键统一生效）；默认 60s，对应配置项 hercules.cache.local-ttl。 */
    private Duration localTtl = Duration.ofSeconds(60);

    /** Redis L2 详情键 TTL（SET 附带过期时间）；默认 5 分钟（300s），对应配置项 hercules.cache.remote-ttl。 */
    private Duration remoteTtl = Duration.ofMinutes(5);

    /** 列表键 TTL（L1/L2 统一的最终一致窗口，列表键不纳入版本链）；默认 10s，对应配置项 hercules.cache.list-ttl。 */
    private Duration listTtl = Duration.ofSeconds(10);

    /** 本地缓存最大条目数（Caffeine maximumSize，超出按 W-TinyLFU 淘汰）；默认 1000，对应配置项 hercules.cache.local-max-size。 */
    private long localMaxSize = 1000;

    /** 列表键前缀（FixedTTLStrategy 以此识别列表键并套用 list-ttl）；默认 "course:list:"，对应配置项 hercules.cache.list-key-prefix。 */
    private String listKeyPrefix = "course:list:";

    /**
     * 读取 Caffeine L1 固定 TTL。
     *
     * @return 当前值（hercules.cache.local-ttl），默认 60s
     */
    public Duration getLocalTtl() {
        return localTtl;
    }

    /**
     * 设置 Caffeine L1 固定 TTL（Spring 绑定入口）。
     *
     * @param localTtl TTL，须为正值（如 60s）
     */
    public void setLocalTtl(Duration localTtl) {
        this.localTtl = localTtl;
    }

    /**
     * 读取 Redis L2 详情键 TTL。
     *
     * @return 当前值（hercules.cache.remote-ttl），默认 300s
     */
    public Duration getRemoteTtl() {
        return remoteTtl;
    }

    /**
     * 设置 Redis L2 详情键 TTL（Spring 绑定入口）。
     *
     * @param remoteTtl TTL，须为正值（如 300s）
     */
    public void setRemoteTtl(Duration remoteTtl) {
        this.remoteTtl = remoteTtl;
    }

    /**
     * 读取列表键 TTL。
     *
     * @return 当前值（hercules.cache.list-ttl），默认 10s
     */
    public Duration getListTtl() {
        return listTtl;
    }

    /**
     * 设置列表键 TTL（Spring 绑定入口）。
     *
     * @param listTtl TTL，须为正值（如 10s）
     */
    public void setListTtl(Duration listTtl) {
        this.listTtl = listTtl;
    }

    /**
     * 读取本地缓存最大条目数。
     *
     * @return 当前值（hercules.cache.local-max-size），默认 1000
     */
    public long getLocalMaxSize() {
        return localMaxSize;
    }

    /**
     * 设置本地缓存最大条目数（Spring 绑定入口）。
     *
     * @param localMaxSize 条目上限，须为正整数
     */
    public void setLocalMaxSize(long localMaxSize) {
        this.localMaxSize = localMaxSize;
    }

    /**
     * 读取列表键前缀。
     *
     * @return 当前值（hercules.cache.list-key-prefix），默认 "course:list:"
     */
    public String getListKeyPrefix() {
        return listKeyPrefix;
    }

    /**
     * 设置列表键前缀（Spring 绑定入口）。
     *
     * @param listKeyPrefix 键前缀（如 "course:list:"），尾部需带分隔符
     */
    public void setListKeyPrefix(String listKeyPrefix) {
        this.listKeyPrefix = listKeyPrefix;
    }

    /** 熔断装饰器开关（hercules.cache.circuit-breaker.enabled），默认 true；关闭后 L2 直连不熔断。 */
    private boolean circuitBreakerEnabled = true;

    /** 分布式回源锁开关（hercules.cache.load-lock.enabled），默认 true；Redis 不可用时自动跳过。 */
    private boolean loadLockEnabled = true;

    /** 回源锁自动过期时长（hercules.cache.load-lock.ttl），默认 3s（防持有者宕机死锁）。 */
    private Duration loadLockTtl = Duration.ofSeconds(3);

    /** 未抢到锁时探测 L2 的间隔（hercules.cache.load-lock.poll-interval），默认 50ms。 */
    private Duration loadLockPollInterval = Duration.ofMillis(50);

    /** 未抢到锁时等待队长的总预算（hercules.cache.load-lock.poll-budget），默认 2s，超时走安全阀自行回源。 */
    private Duration loadLockPollBudget = Duration.ofSeconds(2);

    /**
     * 读取熔断装饰器开关。
     *
     * @return 当前值（hercules.cache.circuit-breaker.enabled），默认 true
     */
    public boolean isCircuitBreakerEnabled() {
        return circuitBreakerEnabled;
    }

    /**
     * 设置熔断装饰器开关（Spring 绑定入口）。
     *
     * @param circuitBreakerEnabled true=启用熔断装饰器
     */
    public void setCircuitBreakerEnabled(boolean circuitBreakerEnabled) {
        this.circuitBreakerEnabled = circuitBreakerEnabled;
    }

    /**
     * 读取分布式回源锁开关。
     *
     * @return 当前值（hercules.cache.load-lock.enabled），默认 true
     */
    public boolean isLoadLockEnabled() {
        return loadLockEnabled;
    }

    /**
     * 设置分布式回源锁开关（Spring 绑定入口）。
     *
     * @param loadLockEnabled true=启用分布式回源锁
     */
    public void setLoadLockEnabled(boolean loadLockEnabled) {
        this.loadLockEnabled = loadLockEnabled;
    }

    /**
     * 读取回源锁自动过期时长。
     *
     * @return 当前值（hercules.cache.load-lock.ttl），默认 3s
     */
    public Duration getLoadLockTtl() {
        return loadLockTtl;
    }

    /**
     * 设置回源锁自动过期时长（Spring 绑定入口）。
     *
     * @param loadLockTtl 锁 TTL，须为正值
     */
    public void setLoadLockTtl(Duration loadLockTtl) {
        this.loadLockTtl = loadLockTtl;
    }

    /**
     * 读取未抢到锁时的探测间隔。
     *
     * @return 当前值（hercules.cache.load-lock.poll-interval），默认 50ms
     */
    public Duration getLoadLockPollInterval() {
        return loadLockPollInterval;
    }

    /**
     * 设置未抢到锁时的探测间隔（Spring 绑定入口）。
     *
     * @param loadLockPollInterval 探测间隔，须为正值
     */
    public void setLoadLockPollInterval(Duration loadLockPollInterval) {
        this.loadLockPollInterval = loadLockPollInterval;
    }

    /**
     * 读取未抢到锁时的等待总预算。
     *
     * @return 当前值（hercules.cache.load-lock.poll-budget），默认 2s
     */
    public Duration getLoadLockPollBudget() {
        return loadLockPollBudget;
    }

    /**
     * 设置未抢到锁时的等待总预算（Spring 绑定入口）。
     *
     * @param loadLockPollBudget 等待预算，须为正值
     */
    public void setLoadLockPollBudget(Duration loadLockPollBudget) {
        this.loadLockPollBudget = loadLockPollBudget;
    }
}
