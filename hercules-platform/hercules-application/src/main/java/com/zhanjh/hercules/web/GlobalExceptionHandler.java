package com.zhanjh.hercules.web;

import com.zhanjh.hercules.common.BusinessException;
import com.zhanjh.hercules.common.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常兜底处理器（@RestControllerAdvice）：把各类异常统一转换为 R 响应体，Controller 内无需逐个 try-catch。
 *
 * <p>Spring 按异常类型选择最具体的 @ExceptionHandler，因此 BusinessException 与参数校验异常不会落入兜底分支。四类处理：
 * <ul>
 *   <li>BusinessException —— 预期业务失败：HTTP 状态 = 异常 code（4xx/5xx），响应体 R.fail(code, message)，不打印堆栈；</li>
 *   <li>MethodArgumentNotValidException —— @Valid 请求体校验失败：固定 400，message 取第一条字段错误的 defaultMessage，
 *       无字段错误时回退为「参数校验失败」；</li>
 *   <li>HttpMediaTypeNotSupportedException —— Content-Type 与请求体不符：415（防止落入 500 兜底暴露内部细节）；</li>
 *   <li>Exception —— 未预期异常：记录 ERROR 级堆栈后返回 500 与「服务内部错误: 原始消息」。</li>
 * </ul>
 *
 * <p>处理器为无状态单例（仅持有静态 final Logger），线程安全。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 日志记录器：仅未预期异常分支使用，业务异常不产生日志。 */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务异常：实现「非 0 code 直接作为 HTTP 状态码」的核心约定。
     * 业务异常属预期失败，刻意不打印堆栈，避免正常业务拒绝（如容量已满）刷屏 ERROR 日志。
     *
     * @param e 业务异常，code 为 4xx/5xx，message 为错误描述
     * @return HTTP 状态 = e.getCode()、响应体 = R.fail(e.getCode(), e.getMessage()) 的响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<R<Void>> handleBusiness(BusinessException e) {
        return ResponseEntity.status(e.getCode()).body(R.fail(e.getCode(), e.getMessage()));
    }

    /**
     * 处理 Content-Type 与请求体不符（如 JSON 体以 text/plain 发送）→ 415，
     * 避免落入兜底 500 分支（该分支会暴露内部异常细节）。
     *
     * @param e 媒体类型不支持异常
     * @return HTTP 415、code=415 的统一响应体
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(415).body(R.fail(415, "Content-Type 不受支持: " + e.getContentType()));
    }

    /**
     * 处理 @Valid 请求体校验失败（MethodArgumentNotValidException）。
     *
     * @param e 校验异常，携带 BindingResult 字段错误列表
     * @return HTTP 400、code=400、message 为第一条字段错误提示（或固定回退文案）的响应体
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleValidation(MethodArgumentNotValidException e) {
        // 只取第一条字段错误作为提示：MVP 不做全量错误聚合，保持响应体精简
        String msg = e.getBindingResult().getFieldErrors().isEmpty()
                ? "参数校验失败"
                : e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return ResponseEntity.badRequest().body(R.fail(400, msg));
    }

    /**
     * 兜底处理所有未被上方分支匹配的异常：记录 ERROR 级堆栈便于排查，向调用方返回 500 与简化后的错误消息。
     *
     * @param e 任意未在上方分支处理的异常
     * @return HTTP 500、code=500、message 为「服务内部错误: 原始异常消息」的响应体
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnknown(Exception e) {
        log.error("unexpected error", e);
        return ResponseEntity.internalServerError().body(R.fail(500, "服务内部错误: " + e.getMessage()));
    }
}
