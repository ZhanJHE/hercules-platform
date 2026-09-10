package com.zhanjh.hercules.sync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 同步模块配置项（前缀 hercules.sync），经 SyncConfig 的 @EnableConfigurationProperties 注册生效。
 *
 * <p>三项可调参数与阶段 B 新增的传输开关：
 * <ul>
 *   <li>nodeId 决定向量时钟的分量名——多实例部署时给每个实例配不同值即可产生互不支配的
 *       并发分量，用于演示/验证冲突消解；</li>
 *   <li>maxNodes 是风险 R-03 的防膨胀告警阈值，消费端 upsert 时分量数超限即打 warn；</li>
 *   <li>transport 选择缓存同步的触发源：in-process（默认，进程内事件总线，业务事务内发布）
 *       / canal-mq（Binlog 链路：MySQL → canal → RocketMQ → 应用消费者，事务内零同步动作）；
 *       rocketmq 子项仅在 canal-mq 模式下被读取。</li>
 * </ul>
 *
 * @author zhanjh
 * @since 0.0.1
 */
@ConfigurationProperties(prefix = "hercules.sync")
public class HerculesSyncProperties {

    /** 传输模式常量：进程内事件总线（默认，单机/测试形态）。 */
    public static final String TRANSPORT_IN_PROCESS = "in-process";

    /** 传输模式常量：Canal → RocketMQ 真实 Binlog 链路（容器部署形态）。 */
    public static final String TRANSPORT_CANAL_MQ = "canal-mq";

    /** 本节点标识，即向量时钟的分量名；默认 "node-1"，多实例部署时应按实例分别配置（hercules.sync.node-id）。 */
    private String nodeId = "node-1";

    /** 向量时钟最大节点数阈值，默认 10；消费端 upsert 时分量数超过该值打 warn（风险 R-03：规划归档收敛）（hercules.sync.max-nodes）。 */
    private int maxNodes = 10;

    /** 缓存同步触发源：in-process（默认）/ canal-mq（hercules.sync.transport），setter 即校验取值。 */
    private String transport = TRANSPORT_IN_PROCESS;

    /** RocketMQ 连接项（仅 canal-mq 模式读取）。 */
    private final RocketMQ rocketmq = new RocketMQ();

    /**
     * 读取本节点标识。
     *
     * @return 节点标识（向量时钟分量名），未配置时为默认值 "node-1"
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * 覆盖本节点标识。
     *
     * @param nodeId 节点标识（对应配置项 hercules.sync.node-id），不允许为 null 或空白
     * @throws IllegalArgumentException nodeId 为空白时抛出（配置绑定阶段即失败，拒绝带错误配置启动）
     */
    public void setNodeId(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("hercules.sync.node-id 不允许为空白");
        }
        this.nodeId = nodeId;
    }

    /**
     * 读取时钟最大节点数阈值。
     *
     * @return 阈值（节点个数），未配置时为默认值 10
     */
    public int getMaxNodes() {
        return maxNodes;
    }

    /**
     * 覆盖时钟最大节点数阈值。
     *
     * @param maxNodes 阈值（节点个数，对应配置项 hercules.sync.max-nodes），必须为正数
     * @throws IllegalArgumentException maxNodes 小于 1 时抛出（配置绑定阶段即失败，拒绝带错误配置启动）
     */
    public void setMaxNodes(int maxNodes) {
        if (maxNodes < 1) {
            throw new IllegalArgumentException("hercules.sync.max-nodes 必须 >= 1，实际值: " + maxNodes);
        }
        this.maxNodes = maxNodes;
    }

    /**
     * 读取传输模式。
     *
     * @return "in-process" 或 "canal-mq"
     */
    public String getTransport() {
        return transport;
    }

    /**
     * 覆盖传输模式。
     *
     * @param transport 传输模式（hercules.sync.transport），仅允许 in-process / canal-mq
     * @throws IllegalArgumentException 取值非法时抛出（配置绑定阶段即失败）
     */
    public void setTransport(String transport) {
        if (!TRANSPORT_IN_PROCESS.equals(transport) && !TRANSPORT_CANAL_MQ.equals(transport)) {
            throw new IllegalArgumentException(
                    "hercules.sync.transport 仅允许 " + TRANSPORT_IN_PROCESS + " / " + TRANSPORT_CANAL_MQ + "，实际值: " + transport);
        }
        this.transport = transport;
    }

    /**
     * 判断是否 canal-mq 传输模式（业务写路径据此跳过事务内发布，SyncConfig 据此装配消费者）。
     *
     * @return true-Canal/RocketMQ 链路模式
     */
    public boolean isCanalMq() {
        return TRANSPORT_CANAL_MQ.equals(transport);
    }

    /**
     * 读取 RocketMQ 连接项。
     *
     * @return 连接项（name-server/topic/消费组），仅 canal-mq 模式被读取
     */
    public RocketMQ getRocketmq() {
        return rocketmq;
    }

    /**
     * RocketMQ 连接项（阶段 B）：消费者连接 name-server、订阅 topic 的固定配置块。
     *
     * <p>线程安全性：仅承载配置值，绑定完成后只读。
     *
     * @author zhanjh
     * @since 0.0.1
     */
    public static class RocketMQ {

        /** NameServer 地址（hercules.sync.rocketmq.name-server），容器部署为 rocketmq-namesrv:9876。 */
        private String nameServer = "localhost:9876";

        /** 订阅的 binlog topic（hercules.sync.rocketmq.topic），与 canal.mq.topic 配置一致。 */
        private String topic = "hercules-binlog-topic";

        /** 消费组（hercules.sync.rocketmq.consumer-group），同组多实例自动负载分摊队列。 */
        private String consumerGroup = "hercules-cache-sync";

        public String getNameServer() {
            return nameServer;
        }

        public void setNameServer(String nameServer) {
            if (nameServer == null || nameServer.isBlank()) {
                throw new IllegalArgumentException("hercules.sync.rocketmq.name-server 不允许为空白");
            }
            this.nameServer = nameServer;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            if (topic == null || topic.isBlank()) {
                throw new IllegalArgumentException("hercules.sync.rocketmq.topic 不允许为空白");
            }
            this.topic = topic;
        }

        public String getConsumerGroup() {
            return consumerGroup;
        }

        public void setConsumerGroup(String consumerGroup) {
            if (consumerGroup == null || consumerGroup.isBlank()) {
                throw new IllegalArgumentException("hercules.sync.rocketmq.consumer-group 不允许为空白");
            }
            this.consumerGroup = consumerGroup;
        }
    }
}
