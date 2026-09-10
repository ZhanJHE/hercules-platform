package com.zhanjh.hercules.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhanjh.hercules.model.CacheVersion;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 缓存版本表 Mapper：继承 MyBatis-Plus BaseMapper 获得通用 CRUD，并补充带乐观锁条件的版本行更新。
 *
 * <p>两个使用方：
 * <ul>
 *   <li>VersionChangeConsumer.upsert —— selectOne（按 cache_key）判存：行不存在则 insert（currentVersion=1，
 *       唯一键 uk_cache_key 冲突时重查转更新）；存在则走 {@link #updateVersionRow} 乐观锁更新
 *       （currentVersion 同时 +1 并覆盖时钟 JSON 与 updateTime）；</li>
 *   <li>VersionReader.find —— selectOne 读取存量时钟 JSON，供生产者构建 incoming 时钟。</li>
 * </ul>
 *
 * <p>并发说明（遗留事项清理）：原实现为 select-then-insert/update 无锁，多实例并发同键时可能丢失
 * 时钟记录；现 update 路径以 current_version 作乐观锁条件、insert 路径以 uk_cache_key 唯一键冲突
 * 检测兜底，冲突由消费端重查重试收敛（见 VersionChangeConsumer.upsert）。
 * MyBatis 动态代理生成实现，单例无状态，线程安全。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public interface CacheVersionMapper extends BaseMapper<CacheVersion> {

    /**
     * 乐观锁更新版本记录：仅当 current_version 仍等于期望值时执行更新，并将 current_version 原值 +1。
     *
     * <p>条件失败（0 行）说明并发方已先更新过该行，调用方应重查记录后重试或放弃本次持久化；
     * 该语义保证多实例并发下版本行不会互相覆盖丢失（单实例串行消费恒为 1 行）。
     *
     * @param id              版本记录主键，非 null
     * @param expectedVersion 调用方读到的 current_version 期望值，非 null
     * @param nodeId          最新写入节点标识
     * @param clockJson       待持久化的向量时钟 JSON
     * @param updateTime      更新时间（同时充当下次冲突合并的旧值 LWW 时间戳）
     * @return 受影响行数：1-更新成功；0-版本已被并发方推进（乐观锁竞争失败）
     */
    @Update("UPDATE t_cache_version SET node_id = #{nodeId}, current_version = current_version + 1, "
            + "vector_clock_json = #{clockJson}, update_time = #{updateTime} "
            + "WHERE id = #{id} AND current_version = #{expectedVersion}")
    int updateVersionRow(@Param("id") Long id,
                         @Param("expectedVersion") Long expectedVersion,
                         @Param("nodeId") String nodeId,
                         @Param("clockJson") String clockJson,
                         @Param("updateTime") LocalDateTime updateTime);
}
