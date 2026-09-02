package com.zhanjh.hercules.common;

/**
 * 业务异常（RuntimeException 子类）：code 直接作为 HTTP 状态码返回给调用方。
 *
 * <p>继承 RuntimeException 使其在 @Transactional 事务方法内抛出时默认触发回滚（Spring 对非受检异常回滚）。
 * 典型用法见 EnrollmentService / CourseController：404-课程或选课记录不存在；409-业务冲突（课程容量已满、
 * 已选人数为 0 无法退课）；单参构造缺省 400-参数或业务规则不满足。
 *
 * <p>由 GlobalExceptionHandler.handleBusiness 统一转换为 R.fail(code, message)；
 * 业务异常视为预期失败，处理器不打印堆栈日志。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public class BusinessException extends RuntimeException {

    /** HTTP 状态码语义的业务错误码（4xx/5xx），由 GlobalExceptionHandler 原样用作响应状态。 */
    private final int code;

    /**
     * 构造指定状态码的业务异常。
     *
     * @param code    HTTP 状态码语义的错误码（如 404、409），取值需在 4xx/5xx 范围内
     * @param message 面向调用方的错误描述
     */
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 构造缺省状态码（400）的业务异常。
     *
     * @param message 面向调用方的错误描述
     */
    public BusinessException(String message) {
        this(400, message);
    }

    /**
     * 获取业务错误码。
     *
     * @return HTTP 状态码语义的错误码（4xx/5xx）
     */
    public int getCode() {
        return code;
    }
}
