package com.zhanjh.hercules.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhanjh.hercules.model.CacheVersion;

/**
 * 缓存版本表 Mapper：仅继承 MyBatis-Plus BaseMapper，无自定义 SQL。
 *
 * <p>两个使用方：
 * <ul>
 *   <li>VersionChangeConsumer.upsert —— selectOne（按 cache_key）判存：行不存在则 insert（currentVersion=1），
 *       存在则 updateById（currentVersion +1 并覆盖时钟 JSON 与 updateTime）；</li>
 *   <li>VersionReader.find —— selectOne 读取存量时钟 JSON，供生产者构建 incoming 时钟。</li>
 * </ul>
 *
 * <p>注意：upsert 为 select-then-insert/update，无数据库层加锁，MVP 单实例（进程内事件总线）下安全；
 * 若扩展为多实例部署，需引入乐观锁（version 列）或依赖 cache_key 唯一键冲突重试。
 * MyBatis 动态代理生成实现，单例无状态，线程安全。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public interface CacheVersionMapper extends BaseMapper<CacheVersion> {
}
