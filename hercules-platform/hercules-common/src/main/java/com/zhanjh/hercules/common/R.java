package com.zhanjh.hercules.common;

/**
 * 统一响应体（Record）：所有 REST 接口返回值的固定外壳，序列化形如 {"code":0,"message":"ok","data":...}。
 *
 * <p>状态码约定：code=0 表示业务成功；非 0 的 code 直接作为 HTTP 状态码（如 404 资源不存在、409 业务冲突、500 内部错误），
 * 由 GlobalExceptionHandler 与 R.fail 配合完成「异常 → 状态码」映射，MVP 不再维护独立的业务错误码表。
 * data 可为 null（失败响应或无载荷接口）。
 *
 * <p>Record 自带不可变性与基于分量的 equals/hashCode，实例可安全共享（线程安全）。
 *
 * @param code    业务状态码：0 成功，非 0 直接映射 HTTP 状态码
 * @param message 提示信息：成功固定为 "ok"，失败为面向调用方的错误描述
 * @param data    业务数据载荷，可为 null
 * @param <T>     数据载荷类型
 * @author zhanjh
 * @since 0.0.1
 */
public record R<T>(int code, String message, T data) {

    /**
     * 构建成功响应。
     *
     * @param data 业务数据，可为 null
     * @param <T>  数据类型
     * @return code=0、message="ok"、携带 data 的成功响应体
     */
    public static <T> R<T> ok(T data) {
        return new R<>(0, "ok", data);
    }

    /**
     * 构建无数据载荷的成功响应（如写操作仅关心成败）。
     *
     * @return code=0、message="ok"、data=null 的成功响应体
     */
    public static R<Void> ok() {
        return new R<>(0, "ok", null);
    }

    /**
     * 构建失败响应，data 恒为 null。
     *
     * @param code    HTTP 状态码语义的错误码（4xx/5xx），BusinessException 抛出后由 GlobalExceptionHandler 原样写回响应状态
     * @param message 错误描述，原样返回给调用方
     * @param <T>     数据类型（失败时无数据）
     * @return 指定 code/message 的失败响应体
     */
    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null);
    }
}
