package com.zhanjh.hercules.sync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 同步模块配置项（前缀 hercules.sync），经 CacheConfig 的 @EnableConfigurationProperties 注册生效。
 *
 * <p>MVP 提供两个可调参数：nodeId 决定向量时钟的分量名——多实例部署时给每个实例
 * 配不同值即可产生互不支配的并发分量，用于演示/验证冲突消解；maxNodes 是风险 R-03
 * 的防膨胀告警阈值，消费端 upsert 时分量数超限即打 warn。</p>
 *
 * @author zhanjh
 * @since 0.0.1
 */
@ConfigurationProperties(prefix = "hercules.sync")
public class HerculesSyncProperties {

    /** 本节点标识，即向量时钟的分量名；默认 "node-1"，多实例部署时应按实例分别配置（hercules.sync.node-id）。 */
    private String nodeId = "node-1";

    /** 向量时钟最大节点数阈值，默认 10；消费端 upsert 时分量数超过该值打 warn（风险 R-03：规划归档收敛）（hercules.sync.max-nodes）。 */
    private int maxNodes = 10;

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
}
