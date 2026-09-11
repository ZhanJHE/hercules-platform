package com.zhanjh.hercules.sync.convert;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhanjh.hercules.common.CacheKeys;
import com.zhanjh.hercules.common.JsonUtil;
import com.zhanjh.hercules.model.Course;
import com.zhanjh.hercules.model.sync.BinlogEntry;
import com.zhanjh.hercules.model.sync.ChangeType;
import com.zhanjh.hercules.sync.clock.VectorClock;
import com.zhanjh.hercules.sync.config.HerculesSyncProperties;
import com.zhanjh.hercules.sync.consumer.VersionChangeConsumer;
import com.zhanjh.hercules.sync.model.VersionedValue;
import com.zhanjh.hercules.sync.support.VersionReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Binlog 消息转换器（阶段 B）：canal flatMessage JSON → {@link BinlogEntry} → {@link VersionedValue}
 * → 现有 {@link VersionChangeConsumer}。同步链的传输替换点——下游判定/合并/落版本表逻辑零改动。
 *
 * <p>处理流程：
 * <ol>
 *   <li>解析 flatMessage：跳过 DDL；非 hercules.t_course（双重校验，与 canal 过滤正则互为兜底）跳过；</li>
 *   <li>逐行（data 数组可能含多行）构建 {@link BinlogEntry}；DELETE 记 warn 忽略（课程无删除接口，
 *       物理删除属数据治理异常，不应静默刷缓存）；</li>
 *   <li>{@link CourseRowMapper} 还原课程对象 → 拼接 cache_key {@code course:{id}} →
 *       时钟 = 存量时钟（VersionReader）⊕ 本节点自增（与进程内发布路径语义一致）→
 *       时间戳取 binlog 事件执行时间（es，充当下次 LWW 裁决的新值时间）；</li>
 *   <li>交给 {@link VersionChangeConsumer#onMessage}——AFTER 应用 / 幂等丢弃 / 并发合并。</li>
 * </ol>
 *
 * <p>幂等与顺序：canal 投递为 at-least-once，同一 (表, 主键) 经 partitionHash 落同一队列保序；
 * 重复/回放消息因时钟判定为 EQUAL/BEFORE 被消费端丢弃。消费失败由监听器层
 * 返回 SUSPEND_CURRENT_QUEUE_A_MOMENT 挂起当前队列重试
 * （监听器层职责），本类不吞异常——异常向上传播触发重试。
 *
 * <p>线程安全性：无实例可变状态；canal-mq 模式下由顺序消费者按队列串行调用。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Component
public class BinlogToVersionConverter {

    private static final Logger log = LoggerFactory.getLogger(BinlogToVersionConverter.class);

    /** 关注的库名：与 canal 过滤正则（hercules\.t_course）一致，代码侧再校验一次。 */
    private static final String WATCHED_DATABASE = "hercules";
    /** 关注的表名：版本链仅覆盖课程详情键。 */
    private static final String WATCHED_TABLE = "t_course";

    /** 版本变更消费者：转换结果的最终处理者（判定/合并/刷缓存/落版本表）。 */
    private final VersionChangeConsumer consumer;
    /** 存量时钟读取器：构建 incoming 时钟的起点。 */
    private final VersionReader versionReader;
    /** 同步模块配置：本节点分量名（node-id）。 */
    private final HerculesSyncProperties syncProps;

    public BinlogToVersionConverter(VersionChangeConsumer consumer,
                                    VersionReader versionReader,
                                    HerculesSyncProperties syncProps) {
        this.consumer = consumer;
        this.versionReader = versionReader;
        this.syncProps = syncProps;
    }

    /**
     * 处理一条 canal flatMessage JSON（可能携带多行变更）。
     *
     * @param flatMessageJson canal MQ 模式的 flatMessage JSON 字符串，不应为 null
     * @return 本条消息转出并交给消费者的版本化值数量（用于日志与测试断言）
     * @throws com.fasterxml.jackson.core.JsonProcessingException JSON 解析失败时向上抛出（由监听器按重试/跳过策略处理）
     */
    public int onFlatMessage(String flatMessageJson) throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode root = JsonUtil.mapper().readTree(flatMessageJson);
        if (root.path("isDdl").asBoolean(false)) {
            // DDL 变更（建表/改列）不产生行级版本；t_course 结构变更属运维事件，人工处理
            log.info("[hercules-sync] skip DDL flatMessage for {}.{}", root.path("database").asText(), root.path("table").asText());
            return 0;
        }
        String database = root.path("database").asText();
        String table = root.path("table").asText();
        if (!WATCHED_DATABASE.equals(database) || !WATCHED_TABLE.equals(table)) {
            return 0;
        }
        ChangeType type = parseType(root.path("type").asText());
        if (type == null) {
            log.warn("[hercules-sync] unknown binlog type={} for {}.{}", root.path("type").asText(), database, table);
            return 0;
        }
        long executeTime = root.path("es").asLong(0L);
        String position = root.path("logfileName").asText("") + ":" + root.path("logfileOffset").asLong();

        List<BinlogEntry> entries = parseRows(root, type, executeTime, position);
        int published = 0;
        for (BinlogEntry entry : entries) {
            if (publish(entry)) {
                published++;
            }
        }
        return published;
    }

    /**
     * 把 flatMessage 的 data 数组解析为逐行的 BinlogEntry 列表。
     *
     * @param root        flatMessage 根节点
     * @param type        已解析的变更类型
     * @param executeTime binlog 事件执行时间（毫秒）
     * @param position    位点描述（日志用）
     * @return 逐行条目列表（无 data 时为空列表）
     */
    private List<BinlogEntry> parseRows(JsonNode root, ChangeType type, long executeTime, String position) {
        List<BinlogEntry> entries = new ArrayList<>();
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            return entries;
        }
        for (JsonNode row : data) {
            BinlogEntry entry = new BinlogEntry();
            entry.setDatabase(root.path("database").asText());
            entry.setTable(root.path("table").asText());
            entry.setType(type);
            entry.setExecuteTime(executeTime);
            entry.setPosition(position);
            entry.setParsedAt(java.time.LocalDateTime.now());
            Map<String, String> rowData = new HashMap<>();
            row.properties().forEach(field -> rowData.put(field.getKey(), asTextOrNull(field.getValue())));
            entry.setData(rowData);
            entries.add(entry);
        }
        return entries;
    }

    /**
     * 把单行 BinlogEntry 转成版本化值并交给消费者。
     *
     * @param entry 单行变更条目
     * @return true-已发布给消费者；false-已跳过（DELETE / 行还原失败）
     */
    private boolean publish(BinlogEntry entry) {
        if (entry.getType() == ChangeType.DELETE) {
            // 课程无删除接口；物理删除行不应刷缓存（下次读取 404 由回源路径自然呈现）
            log.warn("[hercules-sync] ignore DELETE binlog for {}.{} row={} (no delete API expected)",
                    entry.getDatabase(), entry.getTable(), entry.getData());
            return false;
        }
        Course course = CourseRowMapper.map(entry.getData());
        if (course == null) {
            log.warn("[hercules-sync] cannot map binlog row to course (missing/invalid id), position={}", entry.getPosition());
            return false;
        }
        String key = CacheKeys.course(course.getId());
        // 存量时钟 ⊕ 本节点自增：与进程内发布路径（EnrollmentService.publishCourseVersion）语义一致
        VectorClock clock = versionReader.loadClock(key).increment(syncProps.getNodeId());
        consumer.onMessage(new VersionedValue(key, JsonUtil.toJson(course),
                syncProps.getNodeId(), entry.getExecuteTime(), clock));
        return true;
    }

    /**
     * 变更类型字符串 → 枚举（canal flatMessage 输出大写枚举名，兼容小写输入）。
     *
     * @param type 类型字符串
     * @return 枚举值；未知类型返回 null（由调用方记 warn）
     */
    private ChangeType parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return ChangeType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * JSON 节点 → 字符串（canal flatMessage 行值均为字符串语义；缺失/Null 节点返回 null）。
     *
     * @param node JSON 值节点
     * @return 字符串值或 null
     */
    private String asTextOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
