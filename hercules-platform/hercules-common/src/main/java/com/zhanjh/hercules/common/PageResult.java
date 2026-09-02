package com.zhanjh.hercules.common;

import java.util.List;

/**
 * 稳定分页结果 DTO（Record）：替代直接序列化 MyBatis-Plus 的 Page 对象。
 *
 * <p>Page 含大量框架内部字段（orders、optimizeCountSql、searchCount 等），直接序列化会把实现细节泄漏进 API 响应与缓存值，
 * 且字段随 MyBatis-Plus 版本升级可能变化，导致 Redis/L1 中的旧 JSON 反序列化失败。
 * 本类型把序列化形状固定为 4 个字段：列表键 course:list:{p}:{s}:{kw} 的缓存值即本类型的 JSON 串，
 * 读取端以 TypeReference 还原，升级框架不影响缓存兼容。
 *
 * <p>Record 不可变，线程安全。
 *
 * @param total   满足条件的总记录数
 * @param current 当前页码，从 1 起（对应 MyBatis-Plus Page.getCurrent()）
 * @param size    每页条数
 * @param records 当前页数据列表，来自 Page.getRecords()，可能为空列表但不为 null
 * @param <T>     记录类型
 * @author zhanjh
 * @since 0.0.1
 */
public record PageResult<T>(long total, long current, long size, List<T> records) {
}
