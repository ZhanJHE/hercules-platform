package com.zhanjh.hercules;

import com.zhanjh.hercules.cache.remote.DistributedCacheManager;
import com.zhanjh.hercules.testsupport.InMemoryDistributedCacheManager;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVP 端到端冒烟测试：在完整 Spring 上下文中走通「列表查询 → 缓存命中 → 选课写库 → 同步刷缓存 → 并发冲突合并 → 退课回退」全链路。
 *
 * <p>被测对象：课程列表/详情接口、选课/退课接口，及其背后的多级缓存（MultiLevelCacheManager）
 * 与向量时钟同步链（VersionChangeConsumer）。
 * 测试策略：@SpringBootTest + MockMvc，@ActiveProfiles("test") 切换到 H2 内存库（MODE=MySQL）；
 * 内嵌 {@code @TestConfiguration} 以 {@code @Primary} 提供 InMemoryDistributedCacheManager 桩替换 Redis 实现，
 * 全程不依赖本机 MySQL/Redis；统计类断言（dbLoad/l1Hit/conflictDetected 等）经 {@code readLong}/{@code readDouble}
 * 按 JSON Pointer 解析 R 包装响应。
 *
 * <p>覆盖场景（按 {@code @Order} 顺序）：
 * <ul>
 *   <li>{@code courseListReturns50SeedCourses}：种子课程共 50 条，分页 size=10，首条课程名为「程序设计基础」；</li>
 *   <li>{@code secondListReadHitsCacheInsteadOfDb}：二次列表读取命中 L1——dbLoad 相比基线不变、l1Hit≥1、命中率>0；</li>
 *   <li>{@code enrollmentUpdatesDbAndSyncRefreshesCache}：选课后 enrolled 87→88，事务提交后（AFTER_COMMIT）同步链写入 L2 并失效 L1；</li>
 *   <li>{@code concurrentConflictIsMergedByFieldLww}：simulate-conflict 注入并发版本，合并后 courseName 为「程序设计基础(合并后)」、enrolled 保留 88，conflictDetected≥1；</li>
 *   <li>{@code withdrawRevertsEnrolledAndNewerVersionOverridesMergedValue}：退课产生 node-1 支配版本，enrolled 回落 87、课程名恢复原名；</li>
 *   <li>{@code enrollmentRejectsWhenCourseIsFull}：课程 39 容量 150/已选 147，连选 3 次占满名额后第 4 次返回 409。</li>
 * </ul>
 *
 * @author zhanjh
 * @since 0.0.1
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HerculesApplicationTests {

    /**
     * 测试桩配置：以 {@code @Primary} 的内存桩 Bean 覆盖生产 Redis L2 实现，使冒烟测试无需外部 Redis。
     */
    @TestConfiguration
    static class StubCacheConfig {
        /**
         * 注册 InMemoryDistributedCacheManager 为容器中实际生效的 DistributedCacheManager（{@code @Primary} 优先于真实实现）。
         *
         * @return 基于 ConcurrentHashMap 的内存 L2 桩实例
         */
        @Bean
        @Primary
        DistributedCacheManager inMemoryDistributedCacheManager() {
            return new InMemoryDistributedCacheManager();
        }
    }

    /** MockMvc：以模拟 HTTP 请求驱动控制器，无需真实 Servlet 容器。 */
    @Autowired
    private MockMvc mvc;

    /**
     * 验证点：课程列表返回种子数据的正确分页——总数 50、本页 10 条、首条课程名为「程序设计基础」。
     *
     * @throws Exception MockMvc 请求执行或 JSON 断言失败时抛出
     */
    @Test
    @Order(1)
    void courseListReturns50SeedCourses() throws Exception {
        mvc.perform(get("/api/v1/courses").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(50))
                .andExpect(jsonPath("$.data.records.length()").value(10))
                .andExpect(jsonPath("$.data.records[0].courseName").value("程序设计基础"));
    }

    /**
     * 验证点：同一列表查询重复读取命中 L1 缓存而不再回源——dbLoad 相比基线保持不变、l1Hit≥1、总体命中率>0。
     *
     * <p>先发一次列表请求确保结果已装入缓存，并读取 /api/v1/cache/stats 作为 dbLoad 基线；
     * 再发一次列表请求后比对统计增量。
     *
     * @throws Exception MockMvc 请求执行、JSON 解析或断言失败时抛出
     */
    @Test
    @Order(2)
    void secondListReadHitsCacheInsteadOfDb() throws Exception {
        // 预热：读取一次列表，确保结果已装入 L1（之后的重复读取才可能命中）
        mvc.perform(get("/api/v1/courses").param("page", "1").param("size", "10")).andExpect(status().isOk());
        MvcResult first = mvc.perform(get("/api/v1/cache/stats")).andExpect(status().isOk()).andReturn();
        long dbLoadAfterFirst = readLong(first, "$.data.dbLoad");

        mvc.perform(get("/api/v1/courses").param("page", "1").param("size", "10")).andExpect(status().isOk());

        MvcResult second = mvc.perform(get("/api/v1/cache/stats")).andExpect(status().isOk()).andReturn();
        assertThat(readLong(second, "$.data.dbLoad")).isEqualTo(dbLoadAfterFirst);
        assertThat(readLong(second, "$.data.l1Hit")).isGreaterThanOrEqualTo(1);
        assertThat(readDouble(second, "$.data.cacheHitRate")).isGreaterThan(0.0);
    }

    /**
     * 验证点：选课写库成功（status=1）后，同步链在事务提交后（AFTER_COMMIT）把新版本写入 L2 并失效 L1，
     * 随后读取课程详情即为新值 enrolled=88（种子值 87 + 1）。
     *
     * @throws Exception MockMvc 请求执行或 JSON 断言失败时抛出
     */
    @Test
    @Order(3)
    void enrollmentUpdatesDbAndSyncRefreshesCache() throws Exception {
        mvc.perform(post("/api/v1/enrollment")
                        .contentType("application/json")
                        .content("{\"studentId\":20240001,\"courseId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value(1));

        // 消费端在事务提交后（AFTER_COMMIT）已把新版本写入 L2 并失效 L1 → 下一次读取即为 88
        mvc.perform(get("/api/v1/courses/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enrolled").value(88));
    }

    /**
     * 验证点：simulate-conflict 注入与本地版本并发的模拟节点版本，经字段级 LWW 合并后——
     * courseName 变为「程序设计基础(合并后)」（较新时间戳获胜）、enrolled 保留 88，且 conflictDetected≥1。
     *
     * @throws Exception MockMvc 请求执行、JSON 解析或断言失败时抛出
     */
    @Test
    @Order(4)
    void concurrentConflictIsMergedByFieldLww() throws Exception {
        mvc.perform(post("/api/v1/debug/simulate-conflict")
                        .contentType("application/json")
                        .content("{\"courseId\":1,\"courseName\":\"程序设计基础(合并后)\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 合并结果：courseName 来自较新的 node-sim，其余字段（enrolled=88）保留
        mvc.perform(get("/api/v1/courses/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.courseName").value("程序设计基础(合并后)"))
                .andExpect(jsonPath("$.data.enrolled").value(88));

        MvcResult stats = mvc.perform(get("/api/v1/cache/stats")).andExpect(status().isOk()).andReturn();
        assertThat(readLong(stats, "$.data.conflictDetected")).isGreaterThanOrEqualTo(1);
    }

    /**
     * 验证点：退课在 node-1 上产生更新版本（支配合并时钟），DB 权威值重新覆盖此前的合并结果——
     * enrolled 回落为 87、courseName 恢复原名「程序设计基础」。
     *
     * @throws Exception MockMvc 请求执行或 JSON 断言失败时抛出
     */
    @Test
    @Order(5)
    void withdrawRevertsEnrolledAndNewerVersionOverridesMergedValue() throws Exception {
        mvc.perform(delete("/api/v1/enrollment").param("studentId", "20240001").param("courseId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 退课产生 node-1 的更新版本（支配合并时钟），DB 值重新成为权威值
        mvc.perform(get("/api/v1/courses/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enrolled").value(87))
                .andExpect(jsonPath("$.data.courseName").value("程序设计基础"));
    }

    /**
     * 验证点：课程 39（GE202）种子容量 150、已选 147，仅剩 3 个名额——
     * 连选 3 次均成功并占满名额，第 4 次选课被拒并返回 409 冲突。
     *
     * @throws Exception MockMvc 请求执行或 JSON 断言失败时抛出
     */
    @Test
    @Order(6)
    void enrollmentRejectsWhenCourseIsFull() throws Exception {
        // 课程 39（GE202）种子容量 150、已选 147，仅剩 3 个名额
        for (long studentId = 900001L; studentId <= 900003L; studentId++) {
            mvc.perform(post("/api/v1/enrollment")
                            .contentType("application/json")
                            .content("{\"studentId\":" + studentId + ",\"courseId\":39}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }
        // 名额已满：第 4 次选课应被拒绝（HTTP 409，业务码 409）
        mvc.perform(post("/api/v1/enrollment")
                        .contentType("application/json")
                        .content("{\"studentId\":900004,\"courseId\":39}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    /**
     * 从 R 包装响应体中按表达式提取 long 值（供不便用 jsonPath 匹配器直接断言的统计字段使用）。
     *
     * <p>转换规则：去掉表达式开头的 "$"，剩余的前导 "." 充当 JSON Pointer 的根级斜杠，其余 "." 替换为 "/"，
     * 即 "$.data.dbLoad" → "/data/dbLoad"，再交由 Jackson {@code at()} 解析。
     *
     * @param result   MockMvc 返回的响应结果
     * @param jsonPath 形如 "$.data.dbLoad" 的取值表达式
     * @return 目标节点的 long 值（节点缺失时 Jackson 返回 0）
     * @throws Exception 响应体不是合法 JSON 时抛出
     */
    private long readLong(MvcResult result, String jsonPath) throws Exception {
        String body = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode node =
                com.zhanjh.hercules.common.JsonUtil.mapper().readTree(body);
        // "$.data.dbLoad" → JSON Pointer "/data/dbLoad"
        return node.at(jsonPath.substring(1).replace('.', '/')).asLong();
    }

    /**
     * 从 R 包装响应体中按表达式提取 double 值，转换规则与 {@code readLong} 相同。
     *
     * @param result   MockMvc 返回的响应结果
     * @param jsonPath 形如 "$.data.cacheHitRate" 的取值表达式
     * @return 目标节点的 double 值（节点缺失时 Jackson 返回 0.0）
     * @throws Exception 响应体不是合法 JSON 时抛出
     */
    private double readDouble(MvcResult result, String jsonPath) throws Exception {
        String body = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode node =
                com.zhanjh.hercules.common.JsonUtil.mapper().readTree(body);
        return node.at(jsonPath.substring(1).replace('.', '/')).asDouble();
    }
}
