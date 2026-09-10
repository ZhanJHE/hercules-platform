package com.zhanjh.hercules.model.sync;

/**
 * Binlog 行变更类型枚举（阶段 B）：BinlogEntry 的变更语义标记，来源为 canal flatMessage 的 type 字段
 * （INSERT / UPDATE / DELETE），后续同步链按类型决定构建 VersionedValue 的方式。
 *
 * <p>当前写路径：INSERT 与 UPDATE 同等对待（after 行即最新值）；DELETE 在课程业务中不存在
 * （无删除接口），出现时由转换器记 warn 并忽略——课程行被物理删除属于数据治理异常，
 * 不应静默刷新缓存。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public enum ChangeType {

    /** 插入行。 */
    INSERT,

    /** 更新行。 */
    UPDATE,

    /** 删除行。 */
    DELETE
}
