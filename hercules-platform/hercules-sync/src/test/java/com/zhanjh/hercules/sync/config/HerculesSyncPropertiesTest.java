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
        assertThat(props.getTransport()).isEqualTo(HerculesSyncProperties.TRANSPORT_IN_PROCESS);
        assertThat(props.isCanalMq()).isFalse();
    }

    /**
     * 验证点（阶段 B）：transport 仅允许 in-process / canal-mq，非法取值在 setter 即失败。
     */
    @Test
    void transportOnlyAcceptsKnownValues() {
        HerculesSyncProperties props = new HerculesSyncProperties();
        props.setTransport(HerculesSyncProperties.TRANSPORT_CANAL_MQ);
        assertThat(props.isCanalMq()).isTrue();

        assertThatThrownBy(() -> props.setTransport("kafka"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transport");
        assertThatThrownBy(() -> props.setTransport(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 验证点（阶段 B）：RocketMQ 连接项默认值合法、空白拒绝（canal-mq 模式下的消费者连接参数）。
     */
    @Test
    void rocketmqConnectionItemsAreValidated() {
        HerculesSyncProperties.RocketMQ config = new HerculesSyncProperties().getRocketmq();
        assertThat(config.getNameServer()).isEqualTo("localhost:9876");
        assertThat(config.getTopic()).isEqualTo("hercules-binlog-topic");
        assertThat(config.getConsumerGroup()).isEqualTo("hercules-cache-sync");

        HerculesSyncProperties props = new HerculesSyncProperties();
        assertThatThrownBy(() -> props.getRocketmq().setNameServer(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> props.getRocketmq().setTopic(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> props.getRocketmq().setConsumerGroup(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
