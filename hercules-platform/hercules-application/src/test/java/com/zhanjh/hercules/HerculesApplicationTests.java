package com.zhanjh.hercules;

import com.zhanjh.hercules.common.JsonUtil;
import com.zhanjh.hercules.testsupport.TestStubsConfig;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端冒烟测试（阶段 A+ 认证版）：H2 内存库 + 内存桩（L2 缓存/认证存储），
 * 验证 登录 → 带鉴权访问 → 缓存命中 → 选课 → 版本同步 → 冲突合并 → 退课 → 容量满 全链路。
 *
 * <p>认证说明：各用例先经 /api/v1/auth/login 获取真实 JWT（st001 学生 / admin 管理员，
 * 种子账号由 AuthUserSeeder 写入 H2），请求统一携带 Authorization: Bearer。
 *
 * <p>场景清单（@Order 1-6）：
 * <ol>
 *   <li>未登录 401 + 登录成功 + 种子课程 50 条；</li>
 *   <li>二次列表 L1 命中（dbLoad 不变）+ stats 含熔断状态字段（admin）；</li>
 *   <li>选课后 enrolled 87→88（AFTER_COMMIT 刷缓存）；</li>
 *   <li>simulate-conflict 字段级 LWW 合并（admin 触发，保留 enrolled=88）；</li>
 *   <li>退课产生支配版本（87、原名）；</li>
 *   <li>课程 39 容量 150/已选 147，同一学生连选 3 次后第 4 次 409。</li>
 * </ol>
 *
 * @author zhanjh
 * @since 0.0.1
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestStubsConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HerculesApplicationTests {

    /** MockMvc：以模拟 HTTP 请求驱动控制器（含 Security 过滤器链）。 */
    @Autowired
    private MockMvc mvc;

    /**
     * 以演示账号登录并返回 accessToken。
     *
     * @param username 登录名（st001/admin 等）
     * @param password 明文密码
     * @return JWT accessToken
     * @throws Exception MockMvc 请求失败时抛出
     */
    private String login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();
        return JsonUtil.mapper().readTree(result.getResponse().getContentAsString())
                .at("/data/accessToken").asText();
    }

    /**
     * 验证点（阶段 A+ 认证）：未携带 token 访问业务端点 → 401。
     */
    @Test
    @Order(0)
    void unauthenticatedRequestIsRejected() throws Exception {
        mvc.perform(get("/api/v1/courses/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /**
     * 验证点：学生登录成功；课程列表返回种子数据 50 条、首条「程序设计基础」；
     * 阶段 A+ 日志设计——响应头携带 X-Trace-Id（网关生成/应用透传贯通）。
     */
    @Test
    @Order(1)
    void studentLoginAndCourseListReturns50SeedCourses() throws Exception {
        String token = login("st001", "123456");
        mvc.perform(get("/api/v1/courses").param("page", "1").param("size", "10")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(50))
                .andExpect(jsonPath("$.data.records.length()").value(10))
                .andExpect(jsonPath("$.data.records[0].courseName").value("程序设计基础"));
    }

    /**
     * 验证点（缓存命中 + 阶段 A+ 可观测）：admin 触发两次列表，DB 回源次数不变、L1 命中、命中率>0，
     * 且 stats 响应新增 redisCircuitState 字段。
     */
    @Test
    @Order(2)
    void secondListReadHitsCacheInsteadOfDb() throws Exception {
        String admin = login("admin", "admin123");
        mvc.perform(get("/api/v1/courses").param("page", "1").param("size", "10")
                        .header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
        MvcResult first = mvc.perform(get("/api/v1/cache/stats").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn();
        long dbLoadAfterFirst = readLong(first, "$.data.dbLoad");

        mvc.perform(get("/api/v1/courses").param("page", "1").param("size", "10")
                        .header("Authorization", "Bearer " + admin)).andExpect(status().isOk());

        MvcResult second = mvc.perform(get("/api/v1/cache/stats").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn();
        assertThat(readLong(second, "$.data.dbLoad")).isEqualTo(dbLoadAfterFirst);
        assertThat(readLong(second, "$.data.l1Hit")).isGreaterThanOrEqualTo(1);
        assertThat(readDouble(second, "$.data.cacheHitRate")).isGreaterThan(0.0);
        mvc.perform(get("/api/v1/cache/stats").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.data.redisCircuitState").exists());
    }

    /**
     * 验证点：学生选课成功（事务 + 防超选），事务提交后同步链刷新缓存——
     * 下一次读取即为 enrolled=88（87+1）。
     */
    @Test
    @Order(3)
    void enrollmentUpdatesDbAndSyncRefreshesCache() throws Exception {
        String token = login("st001", "123456");
        mvc.perform(post("/api/v1/enrollment")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"courseId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.studentId").value(20240001)); // studentId 取自 token

        mvc.perform(get("/api/v1/courses/1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enrolled").value(88));
    }

    /**
     * 验证点（核心创新演示）：node-sim 并发冲突经字段级 LWW 合并——
     * courseName 取模拟值、enrolled=88 保留，conflictDetected ≥ 1。
     */
    @Test
    @Order(4)
    void concurrentConflictIsMergedByFieldLww() throws Exception {
        String admin = login("admin", "admin123");
        mvc.perform(post("/api/v1/debug/simulate-conflict")
                        .header("Authorization", "Bearer " + admin)
                        .contentType("application/json")
                        .content("{\"courseId\":1,\"courseName\":\"程序设计基础(合并后)\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mvc.perform(get("/api/v1/courses/1").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.courseName").value("程序设计基础(合并后)"))
                .andExpect(jsonPath("$.data.enrolled").value(88));

        MvcResult stats = mvc.perform(get("/api/v1/cache/stats").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn();
        assertThat(readLong(stats, "$.data.conflictDetected")).isGreaterThanOrEqualTo(1);
    }

    /**
     * 验证点：学生退课产生 node-1 支配版本，覆盖合并值——DB 重新成为权威（87、原名）。
     */
    @Test
    @Order(5)
    void withdrawRevertsEnrolledAndNewerVersionOverridesMergedValue() throws Exception {
        String token = login("st001", "123456");
        mvc.perform(delete("/api/v1/enrollment").param("courseId", "1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mvc.perform(get("/api/v1/courses/1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enrolled").value(87))
                .andExpect(jsonPath("$.data.courseName").value("程序设计基础"));
    }

    /**
     * 验证点（防超选）：课程 39（GE202）容量 150、已选 147，同一学生连选 3 次后
     * 第 4 次返回 409（认证后 studentId 取自 token，同一账号重复选课由容量约束兜底）。
     */
    @Test
    @Order(6)
    void enrollmentRejectsWhenCourseIsFull() throws Exception {
        String token = login("st002", "123456");
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/enrollment")
                            .header("Authorization", "Bearer " + token)
                            .contentType("application/json")
                            .content("{\"courseId\":39}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }
        mvc.perform(post("/api/v1/enrollment")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"courseId\":39}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    /**
     * 从 R 包装响应中按 JSON Pointer 读取 long 值。
     *
     * @param result   MockMvc 结果
     * @param jsonPath "$.data.dbLoad" 形式的路径
     * @return long 值
     * @throws Exception JSON 解析失败时抛出
     */
    private long readLong(MvcResult result, String jsonPath) throws Exception {
        String body = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode node =
                com.zhanjh.hercules.common.JsonUtil.mapper().readTree(body);
        // "$.data.dbLoad" → JSON Pointer "/data/dbLoad"
        return node.at(jsonPath.substring(1).replace('.', '/')).asLong();
    }

    /**
     * 从 R 包装响应中按 JSON Pointer 读取 double 值。
     *
     * @param result   MockMvc 结果
     * @param jsonPath "$.data.cacheHitRate" 形式的路径
     * @return double 值
     * @throws Exception JSON 解析失败时抛出
     */
    private double readDouble(MvcResult result, String jsonPath) throws Exception {
        String body = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode node =
                com.zhanjh.hercules.common.JsonUtil.mapper().readTree(body);
        return node.at(jsonPath.substring(1).replace('.', '/')).asDouble();
    }
}
