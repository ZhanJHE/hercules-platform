package com.zhanjh.hercules.sync.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 同步模块配置项单元测试（遗留事项清理）：验证 setter 即校验——非法配置在绑定阶段即失败，
 * 拒绝带错误配置启动（原实现 maxNodes 无下限校验、nodeId 无空白校验）。
 *
 * @author zhanjh
 * @since 0.0.1
 */
class HerculesSyncPropertiesTest {

    /**
     * 验证点：maxNodes 小于 1（0、负数）→ IllegalArgumentException。
     */
    @Test
    void maxNodesMustBePositive() {
        HerculesSyncProperties props = new HerculesSyncProperties();
        assertThatThrownBy(() -> props.setMaxNodes(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-nodes");
        assertThatThrownBy(() -> props.setMaxNodes(-3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 验证点：nodeId 为 null 或空白 → IllegalArgumentException。
     */
    @Test
    void nodeIdMustNotBeBlank() {
        HerculesSyncProperties props = new HerculesSyncProperties();
        assertThatThrownBy(() -> props.setNodeId(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("node-id");
        assertThatThrownBy(() -> props.setNodeId("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 验证点：默认值合法（node-1 / 10），不触发校验异常。
     */
    @Test
    void defaultsAreValid() {
        HerculesSyncProperties props = new HerculesSyncProperties();
        assertThat(props.getNodeId()).isEqualTo("node-1");
        assertThat(props.getMaxNodes()).isEqualTo(10);
    }
}
