package com.zhanjh.hercules;

import com.zhanjh.hercules.cache.core.CacheManager;
import com.zhanjh.hercules.common.CacheKeys;
import com.zhanjh.hercules.controller.DebugController;
import com.zhanjh.hercules.mapper.CourseMapper;
import com.zhanjh.hercules.model.CacheVersion;
import com.zhanjh.hercules.model.Course;
import com.zhanjh.hercules.sync.clock.ClockRelation;
import com.zhanjh.hercules.sync.clock.VectorClock;
import com.zhanjh.hercules.sync.model.VersionedValue;
import com.zhanjh.hercules.sync.producer.InProcessEventBusProducer;
import com.zhanjh.hercules.sync.support.VersionReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DebugController 冲突模拟钩子的单元测试（Mockito，无 Spring 上下文）。
 *
 * <p>重点回归一个缺陷：模拟时钟原来固定写 {@code {"node-sim":1}}。第一次调用正常；
 * 但合并后 t_cache_version 里就存下了 node-sim，第二次调用时新时钟被存量时钟支配、
 * 被消费端判为过期消息丢弃，而接口仍然返回成功——表现为"点了没反应"的静默失效。
 *
 * <p>现在 node-sim 取「存量时钟里 node-sim 的值 + 1」，因此连续调用必须始终判为 CONCURRENT。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@ExtendWith(MockitoExtension.class)
class DebugControllerTest {

    /** 被测课程 ID。 */
    private static final long COURSE_ID = 7L;

    /** 模拟节点标识（与 DebugController 内部常量一致）。 */
    private static final String SIM_NODE = "node-sim";

    @Mock
    private CourseMapper courseMapper;

    @Mock
    private VersionReader versionReader;

    @Mock
    private InProcessEventBusProducer producer;

    @Mock
    private CacheManager cacheManager;

    /** 被测对象。 */
    private DebugController controller;

    @BeforeEach
    void setUp() {
        controller = new DebugController(courseMapper, versionReader, producer, cacheManager);
    }

    /**
     * 构造课程对象（只用到重命名相关的字段）。
     *
     * @return 课程
     */
    private static Course course() {
        Course course = new Course();
        course.setId(COURSE_ID);
        course.setCourseCode("CS101");
        course.setCourseName("程序设计基础");
        course.setCredit(new BigDecimal("3.0"));
        course.setEnrolled(10);
        course.setCapacity(50);
        return course;
    }

    /**
     * 构造存量版本记录。
     *
     * @param clockJson 存量向量时钟 JSON
     * @return 版本记录
     */
    private static CacheVersion storedVersion(String clockJson) {
        CacheVersion version = new CacheVersion();
        version.setCacheKey(CacheKeys.course(COURSE_ID));
        version.setNodeId("node-1");
        version.setCurrentVersion(2L);
        version.setVectorClockJson(clockJson);
        return version;
    }

    /**
     * 打桩并调用一次模拟冲突，返回实际发布出去的版本值。
     *
     * <p>用 {@code atLeastOnce} 而不是恰好一次：同一个用例里连续调用多次时，
     * Mockito 会记录该 mock 的全部调用，这里取最近一次捕获的值（即本次调用发布的）。
     *
     * @param storedClockJson 存量时钟 JSON；传 null 表示该键没有存量版本记录
     * @return 本次调用发布的版本值
     */
    private VersionedValue simulate(String storedClockJson) {
        when(courseMapper.selectById(COURSE_ID)).thenReturn(course());
        when(versionReader.find(CacheKeys.course(COURSE_ID))).thenReturn(
                storedClockJson == null ? Optional.empty() : Optional.of(storedVersion(storedClockJson)));
        controller.simulateConflict(new DebugController.SimulateConflictRequest(COURSE_ID, "并发改名"));

        ArgumentCaptor<VersionedValue> captor = ArgumentCaptor.forClass(VersionedValue.class);
        verify(producer, atLeastOnce()).publish(captor.capture());
        return captor.getValue();
    }

    /**
     * 验证点：只有本节点的写入（存量时钟为 {node-1:1}）时，模拟版本判为并发冲突，
     * 且时钟里只带 node-sim 一个分量（不带 node-1，否则会支配存量而不是并发）。
     */
    @Test
    void firstCallIsConcurrentAndCarriesOnlySimComponent() {
        VersionedValue published = simulate("{\"node-1\":1}");

        VectorClock stored = VectorClock.fromJson("{\"node-1\":1}");
        assertThat(VectorClock.compare(published.clock(), stored)).isEqualTo(ClockRelation.CONCURRENT);
        assertThat(published.clock().snapshot()).containsOnlyKeys(SIM_NODE);
        assertThat(published.clock().snapshot().get(SIM_NODE)).isEqualTo(1L);
        assertThat(published.nodeId()).isEqualTo(SIM_NODE);
    }

    /**
     * 验证点（缺陷回归）：存量时钟里已经有 node-sim（即上一次模拟合并后的状态）时，
     * 再次调用仍然判为并发冲突——这正是原来静默失效的场景。
     */
    @Test
    void repeatedCallAfterPreviousMergeIsStillConcurrent() {
        VersionedValue published = simulate("{\"node-1\":1,\"node-sim\":1}");

        VectorClock stored = VectorClock.fromJson("{\"node-1\":1,\"node-sim\":1}");
        assertThat(VectorClock.compare(published.clock(), stored)).isEqualTo(ClockRelation.CONCURRENT);
        // 分量必须比上一次大，否则会被判为过期消息
        assertThat(published.clock().snapshot().get(SIM_NODE)).isEqualTo(2L);
    }

    /**
     * 验证点（缺陷回归，连续两轮）：按消费端的真实行为推进存量时钟（存量的 copy 再 merge 模拟时钟），
     * 连打两次演示必须两次都判并发。
     */
    @Test
    void twoConsecutiveCallsAreBothConcurrent() {
        VectorClock stored = VectorClock.fromJson("{\"node-1\":1}");

        VersionedValue first = simulate("{\"node-1\":1}");
        assertThat(VectorClock.compare(first.clock(), stored)).isEqualTo(ClockRelation.CONCURRENT);
        // 消费端 CONCURRENT 分支把两侧时钟取并集后写回版本表
        stored = stored.copy().merge(first.clock());

        VersionedValue second = simulate(stored.toJson());
        assertThat(VectorClock.compare(second.clock(), stored)).isEqualTo(ClockRelation.CONCURRENT);
        assertThat(second.clock().snapshot().get(SIM_NODE)).isEqualTo(2L);
    }

    /**
     * 验证点：该课程从没有过真实写入、没有存量版本记录时，没有可冲突的对象，
     * 判定为 AFTER（按新版本直接应用），响应里要如实说明"不是并发"，不能谎报为已合并。
     */
    @Test
    void noStoredVersionReportsAfterInsteadOfConflict() {
        when(courseMapper.selectById(COURSE_ID)).thenReturn(course());
        when(versionReader.find(CacheKeys.course(COURSE_ID))).thenReturn(Optional.empty());

        var response = controller.simulateConflict(
                new DebugController.SimulateConflictRequest(COURSE_ID, "并发改名"));

        assertThat(response.data().get("relation")).isEqualTo("AFTER");
        assertThat((String) response.data().get("effect")).contains("不是并发");
        assertThat(response.data().get("storedVersionBefore")).isEqualTo("(无存量版本)");
    }

    /**
     * 验证点：并发场景下响应把判定与效果写清楚，便于现场一眼看出有没有真的触发合并。
     */
    @Test
    void concurrentResponseDescribesMergeEffect() {
        when(courseMapper.selectById(COURSE_ID)).thenReturn(course());
        when(versionReader.find(CacheKeys.course(COURSE_ID)))
                .thenReturn(Optional.of(storedVersion("{\"node-1\":3}")));

        var response = controller.simulateConflict(
                new DebugController.SimulateConflictRequest(COURSE_ID, "并发改名"));

        assertThat(response.data().get("relation")).isEqualTo("CONCURRENT");
        assertThat((String) response.data().get("effect")).contains("字段级合并");
        assertThat(response.data().get("storedClock")).isEqualTo("{\"node-1\":3}");
    }
}
