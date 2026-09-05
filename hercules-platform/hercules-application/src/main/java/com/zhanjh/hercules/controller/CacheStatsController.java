package com.zhanjh.hercules.controller;

import com.zhanjh.hercules.cache.core.MultiLevelCacheManager;
import com.zhanjh.hercules.cache.stats.CacheStatsCollector;
import com.zhanjh.hercules.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 缓存运行时统计只读接口：/api/v1/cache/stats。
 *
 * <p>聚合 CacheStatsCollector 的累计计数（AtomicLong，进程启动以来累加、重启归零）
 * 与本机 L1 近似容量，用于答辩演示缓存命中率与同步链活动；同一份计数还经 Micrometer
 * 暴露为 hercules.cache.* / hercules.sync.* 指标（/actuator/prometheus），可交叉核对。
 *
 * <p>响应 data 字段说明：
 * <ul>
 *   <li>l1Hit / l1Miss —— L1 Caffeine 命中/未命中次数；</li>
 *   <li>l2Hit / l2Miss —— L2 Redis 命中/未命中次数（仅 L1 模式下恒为 0）；</li>
 *   <li>dbLoad —— 穿透两级缓存、回源 DB 加载的次数；</li>
 *   <li>versionApplied / conflictDetected —— 同步链应用版本数 / 其中并发冲突合并数；</li>
 *   <li>cacheHitRate —— 总命中率 = (l1Hit + l2Hit) / (l1Hit + l1Miss)，即 L1 未命中后
 *       L2 命中也计为命中；尚无读请求时为 0.0；</li>
 *   <li>redisDegraded —— Redis 降级次数（熔断 OPEN 快速失败或底层异常/超时，读路径跳过
 *       L2 直连 DB；阶段 A+ 高可用加固）；</li>
 *   <li>redisCircuitState —— Redis 熔断器状态（CLOSED/HALF_OPEN/OPEN/UNKNOWN）；</li>
 *   <li>localCacheSize —— 本机 Caffeine 近似条目数（estimatedSize，非精确值）。</li>
 * </ul>
 *
 * <p>线程安全性：只读聚合，无实例状态；计数值为读取瞬间的快照，并发读写下允许微小偏差。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@RestController
@RequestMapping("/api/v1/cache")
public class CacheStatsController {

    /** 缓存/同步链计数器：提供各维度累计值与命中率快照 */
    private final CacheStatsCollector statsCollector;

    /** 多级缓存实现：用于读取本机 L1 条目数（localSize） */
    private final MultiLevelCacheManager cacheManager;

    /**
     * 构造注入。
     *
     * @param statsCollector 缓存/同步链计数器
     * @param cacheManager   多级缓存实现（用于取本机 L1 容量）
     */
    public CacheStatsController(CacheStatsCollector statsCollector, MultiLevelCacheManager cacheManager) {
        this.statsCollector = statsCollector;
        this.cacheManager = cacheManager;
    }

    /**
     * 查询缓存/同步链运行时统计。
     *
     * <p>请求示例：{@code GET /api/v1/cache/stats}
     * <br>响应示例：
     * {@code {"code":0,"message":"ok","data":{"l1Hit":120,"l1Miss":30,"l2Hit":25,"l2Miss":5,
     * "dbLoad":5,"versionApplied":8,"conflictDetected":1,"cacheHitRate":0.97,"localCacheSize":42}}}
     *
     * @return 统一响应体，data 为类注释所列统计字段（LinkedHashMap 保证输出字段顺序稳定）
     */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        CacheStatsCollector.CacheSnapshot snapshot = statsCollector.snapshot();
        // LinkedHashMap 维持插入顺序，保证响应字段顺序固定，便于演示时对照讲解
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("l1Hit", snapshot.l1Hit());
        data.put("l1Miss", snapshot.l1Miss());
        data.put("l2Hit", snapshot.l2Hit());
        data.put("l2Miss", snapshot.l2Miss());
        data.put("dbLoad", snapshot.dbLoad());
        data.put("versionApplied", snapshot.versionApplied());
        data.put("conflictDetected", snapshot.conflictDetected());
        data.put("cacheHitRate", snapshot.cacheHitRate());
        data.put("redisDegraded", snapshot.redisDegraded());
        data.put("redisCircuitState", snapshot.redisCircuitState());
        data.put("localCacheSize", cacheManager.localSize());
        return R.ok(data);
    }
}
