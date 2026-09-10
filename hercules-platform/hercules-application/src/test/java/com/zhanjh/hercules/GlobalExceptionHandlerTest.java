package com.zhanjh.hercules;

import com.zhanjh.hercules.common.R;
import com.zhanjh.hercules.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局异常兜底处理器单元测试（遗留事项清理）：不启动 Spring，直接调用处理器方法。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>未预期异常 → 500 + 固定文案，原始异常消息（含内部主机名、SQL 等细节）不得出现在响应体；</li>
 *   <li>业务异常（BusinessException）→ code 原样映射，消息保留（业务文案本身面向调用方，不受收敛影响）。</li>
 * </ul>
 *
 * @author zhanjh
 * @since 0.0.1
 */
class GlobalExceptionHandlerTest {

    /**
     * 验证点（500 响应收敛）：兜底分支返回固定文案，异常消息中的内部细节不外泄。
     */
    @Test
    void unknownExceptionReturnsFixedMessageWithoutInternalDetails() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<R<Void>> resp = handler.handleUnknown(
                new IllegalStateException("jdbc:mysql://internal-host:3306/hercules 连接失败 at com.zhanjh.internal.Cls"));

        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().message()).isEqualTo("服务内部错误，请稍后重试");
        assertThat(resp.getBody().message()).doesNotContain("internal-host");
        assertThat(resp.getBody().message()).doesNotContain("com.zhanjh");
    }

    /**
     * 验证点（对照）：业务异常不走收敛——409 容量已满等业务文案按原样返回。
     */
    @Test
    void businessExceptionKeepsItsOwnMessage() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<R<Void>> resp = handler.handleBusiness(
                new com.zhanjh.hercules.common.BusinessException(409, "课程容量已满: 人工智能通识"));

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().message()).isEqualTo("课程容量已满: 人工智能通识");
    }
}
