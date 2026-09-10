package com.zhanjh.hercules.sync.convert;

import com.zhanjh.hercules.mapper.CacheVersionMapper;
import com.zhanjh.hercules.model.CacheVersion;
import com.zhanjh.hercules.sync.clock.VectorClock;
import com.zhanjh.hercules.sync.config.HerculesSyncProperties;
import com.zhanjh.hercules.sync.consumer.VersionChangeConsumer;
import com.zhanjh.hercules.sync.model.VersionedValue;
import com.zhanjh.hercules.sync.support.VersionReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BinlogToVersionConverter 单元测试（阶段 B）：以 Mockito 桩替代 CacheVersionMapper 与消费者，
 * 验证 canal flatMessage JSON → VersionedValue → 消费者的完整转换链。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>{@code updateFlatMessageIsConvertedAndPublished}：UPDATE 消息 → 行还原为 Course、
 *       键为 course:{id}、时钟 = 存量 ⊕ node-1、时间戳取 binlog es；</li>
 *   <li>{@code multiRowMessagePublishesPerRow}：data 数组多行 → 逐行发布；</li>
 *   <li>{@code nonWatchedTableAndDdlAreSkipped}：非 hercules/t_course 表与 DDL 消息跳过；</li>
 *   <li>{@code deleteMessageIsIgnored}：DELETE 记 warn 忽略（课程无删除接口）；</li>
 *   <li>{@code storedClockIsIncrementedNotReplaced}：存量时钟 node-1:5 → incoming node-1:6（因果链保持）；</li>
 *   <li>{@code malformedJsonPropagatesForRetryDecision}：非法 JSON 异常向上传播（重试/跳过由监听器决定）。</li>
 * </ul>
 *
 * @author zhanjh
 * @since 0.0.1
 */
@ExtendWith(MockitoExtension.class)
class BinlogToVersionConverterTest {

    /** 模拟 canal flatMessage：t_course UPDATE 单行（值均为字符串，与 canal 实际输出一致）。 */
    private static final String FLAT_MESSAGE_UPDATE = """
            {"data":[{"id":"1","course_code":"CS101","course_name":"程序设计基础","teacher_name":"张伟",
              "credit":"3.0","capacity":"60","enrolled":"88","schedule_json":"{\\"day\\":1}",
              "prerequisites_json":"[]","syllabus_url":"/syllabus/CS101.pdf","update_time":"2026-09-01 09:00:00"}],
              "database":"hercules","es":1726000000000,"isDdl":false,
              "logfileName":"mysql-bin.000003","logfileOffset":1234,
              "pkNames":["id"],"table":"t_course","type":"UPDATE"}""";

    /** Mockito 桩：t_cache_version 查询（存量时钟来源）。 */
    @Mock
    private CacheVersionMapper cacheVersionMapper;

    /** Mockito 桩：消费者（转换结果的落点，捕获 VersionedValue 断言）。 */
    @Mock
    private VersionChangeConsumer consumer;

    /** 被测对象：setUp 手工装配（真实 VersionReader + 真实配置）。 */
    private BinlogToVersionConverter converter;

    /**
     * 装配被测对象：VersionReader 用桩 Mapper（默认无存量记录 → 空时钟），配置用默认值（node-1）。
     */
    @BeforeEach
    void setUp() {
        converter = new BinlogToVersionConverter(consumer,
                new VersionReader(cacheVersionMapper),
                new HerculesSyncProperties());
    }

    /**
     * 验证点：UPDATE flatMessage → 单个 VersionedValue——键 course:1、值 JSON 含还原后的课程字段、
     * 时间戳 = binlog es、时钟为空存量 ⊕ node-1 = {"node-1":1}。
     */
    @Test
    void updateFlatMessageIsConvertedAndPublished() throws Exception {
        when(cacheVersionMapper.selectOne(any())).thenReturn(null);

        int published = converter.onFlatMessage(FLAT_MESSAGE_UPDATE);

        assertThat(published).isEqualTo(1);
        ArgumentCaptor<VersionedValue> captor = ArgumentCaptor.forClass(VersionedValue.class);
        verify(consumer).onMessage(captor.capture());
        VersionedValue value = captor.getValue();
        assertThat(value.key()).isEqualTo("course:1");
        assertThat(value.valueJson())
                .contains("courseCode").contains("CS101")          // 列名蛇形 → 驼峰字段
                .contains("courseName").contains("程序设计基础")
                .contains("\"enrolled\":88")
                .doesNotContain("credit\":null");                  // 数值列已还原，无 null 字段
        assertThat(value.nodeId()).isEqualTo("node-1");
        assertThat(value.timestamp()).isEqualTo(1726000000000L);   // binlog es 作为 LWW 时间戳
        assertThat(value.clock().toJson()).contains("\"node-1\":1");
    }

    /**
     * 验证点：一条 flatMessage 携带多行 data（如批量 UPDATE）→ 逐行发布，行序保持。
     */
    @Test
    void multiRowMessagePublishesPerRow() throws Exception {
        when(cacheVersionMapper.selectOne(any())).thenReturn(null);
        String message = """
                {"data":[{"id":"1","course_name":"A"},{"id":"2","course_name":"B"}],
                 "database":"hercules","es":1726000000000,"isDdl":false,
                 "pkNames":["id"],"table":"t_course","type":"UPDATE"}""";

        int published = converter.onFlatMessage(message);

        assertThat(published).isEqualTo(2);
        ArgumentCaptor<VersionedValue> captor = ArgumentCaptor.forClass(VersionedValue.class);
        verify(consumer, times(2)).onMessage(captor.capture());
        List<VersionedValue> values = captor.getAllValues();
        assertThat(values.get(0).key()).isEqualTo("course:1");
        assertThat(values.get(1).key()).isEqualTo("course:2");
    }

    /**
     * 验证点：非关注表（t_user）与 DDL 消息直接跳过，不产生任何发布。
     */
    @Test
    void nonWatchedTableAndDdlAreSkipped() throws Exception {
        String otherTable = FLAT_MESSAGE_UPDATE.replace("\"table\":\"t_course\"", "\"table\":\"t_user\"");
        String ddl = FLAT_MESSAGE_UPDATE.replace("\"isDdl\":false", "\"isDdl\":true");

        assertThat(converter.onFlatMessage(otherTable)).isZero();
        assertThat(converter.onFlatMessage(ddl)).isZero();
        verify(consumer, times(0)).onMessage(any());
    }

    /**
     * 验证点：DELETE 消息记 warn 忽略（课程无删除接口，物理删除不应刷缓存）。
     */
    @Test
    void deleteMessageIsIgnored() throws Exception {
        String message = FLAT_MESSAGE_UPDATE.replace("\"type\":\"UPDATE\"", "\"type\":\"DELETE\"");

        assertThat(converter.onFlatMessage(message)).isZero();
        verify(consumer, times(0)).onMessage(any());
    }

    /**
     * 验证点（因果链保持）：存量时钟 {"node-1":5} → incoming = 存量 ⊕ node-1 = {"node-1":6}，
     * 而非重新从 1 计数——消费端据此判定 AFTER/EQUAL 关系。
     */
    @Test
    void storedClockIsIncrementedNotReplaced() throws Exception {
        CacheVersion stored = new CacheVersion();
        stored.setCacheKey("course:1");
        stored.setNodeId("node-1");
        stored.setCurrentVersion(9L);
        stored.setVectorClockJson(new VectorClock(java.util.Map.of("node-1", 5L)).toJson());
        when(cacheVersionMapper.selectOne(any())).thenReturn(stored);

        converter.onFlatMessage(FLAT_MESSAGE_UPDATE);

        ArgumentCaptor<VersionedValue> captor = ArgumentCaptor.forClass(VersionedValue.class);
        verify(consumer).onMessage(captor.capture());
        assertThat(captor.getValue().clock().toJson()).contains("\"node-1\":6");
    }

    /**
     * 验证点：非法 JSON 抛 JsonProcessingException 向上传播——重试还是跳过由监听器分层决定。
     */
    @Test
    void malformedJsonPropagatesForRetryDecision() {
        assertThatThrownBy(() -> converter.onFlatMessage("{not-json"))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
}
