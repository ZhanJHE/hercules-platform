package com.zhanjh.hercules.sync.support;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zhanjh.hercules.mapper.CacheVersionMapper;
import com.zhanjh.hercules.model.CacheVersion;
import com.zhanjh.hercules.sync.clock.VectorClock;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 版本记录读取器：为生产侧与调试端点提供 t_cache_version 存量版本/时钟的统一只读入口。
 *
 * <p>两类使用方：业务写路径（如 EnrollmentService）用它加载存量时钟并自增本节点分量，
 * 构建待发布版本（incoming）的向量时钟；调试端点（DebugController）用它读取存量版本号，
 * 判断冲突模拟的前置条件。只读不写，与消费端的 upsert 路径解耦。</p>
 *
 * <p>线程安全性：无实例状态，可并发调用。</p>
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Component
public class VersionReader {

    /** t_cache_version 表 Mapper：按 cache_key 做单行查询。 */
    private final CacheVersionMapper cacheVersionMapper;

    public VersionReader(CacheVersionMapper cacheVersionMapper) {
        this.cacheVersionMapper = cacheVersionMapper;
    }

    /**
     * 按缓存键查询存量版本记录。
     *
     * @param cacheKey 缓存键（t_cache_version.cache_key，如 CacheKeys.course(courseId)），不应为 null
     * @return 存量版本记录；该键从未同步过时返回空 Optional，从不返回 null
     */
    public Optional<CacheVersion> find(String cacheKey) {
        return Optional.ofNullable(
                cacheVersionMapper.selectOne(new QueryWrapper<CacheVersion>().eq("cache_key", cacheKey)));
    }

    /**
     * 加载缓存键的存量向量时钟（生产者构建 incoming 时钟的起点）。
     *
     * <p>实现要点：find → 取 vector_clock_json → VectorClock.fromJson 链式还原；
     * 无记录或 JSON 为空均落到空时钟，调用方可直接在其上 increment 本节点分量。</p>
     *
     * @param cacheKey 缓存键，不应为 null
     * @return 存量向量时钟；无存量版本时为空时钟，从不为 null
     */
    public VectorClock loadClock(String cacheKey) {
        return find(cacheKey)
                .map(CacheVersion::getVectorClockJson)
                .map(VectorClock::fromJson)
                .orElseGet(VectorClock::new);
    }
}
