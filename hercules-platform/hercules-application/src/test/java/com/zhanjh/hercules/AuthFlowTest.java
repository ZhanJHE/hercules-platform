package com.zhanjh.hercules;

import com.zhanjh.hercules.common.JsonUtil;
import com.zhanjh.hercules.testsupport.TestStubsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证流专项测试（阶段 A+）：H2 + 内存桩认证存储，验证双 Token 全生命周期与角色控制。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>错误密码 → 401；</li>
 *   <li>未认证访问受保护端点 → 401；</li>
 *   <li>学生访问 ADMIN 端点 → 403；</li>
 *   <li>刷新旋转：旧 refreshToken 刷新一次后立即失效（第二次刷新 401）；</li>
 *   <li>登出：access 进入黑名单 → 旧 token 再访问 401；refreshToken 同时吊销；</li>
 *   <li>admin 登录可读治理统计。</li>
 * </ul>
 *
 * @author zhanjh
 * @since 0.0.1
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestStubsConfig.class)
class AuthFlowTest {

    /** MockMvc：模拟 HTTP 请求（含 Security 过滤器链）。 */
    @Autowired
    private MockMvc mvc;

    /**
     * 验证点：错误密码返回 401（不泄露用户是否存在等信息差异）。
     */
    @Test
    void wrongPasswordReturns401() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"st001\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /**
     * 验证点：未认证访问受保护端点 → 401（白名单外默认拒绝）。
     */
    @Test
    void unauthenticatedAccessIsRejected() throws Exception {
        mvc.perform(get("/api/v1/courses/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/cache/stats")).andExpect(status().isUnauthorized());
    }

    /**
     * 验证点：角色控制——学生访问 ADMIN 端点（治理统计）→ 403。
     */
    @Test
    void studentCannotAccessAdminEndpoints() throws Exception {
        String token = login("st001", "123456");
        mvc.perform(get("/api/v1/cache/stats").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    /**
     * 验证点：admin 登录可读治理统计（角色正例）。
     */
    @Test
    void adminCanReadStats() throws Exception {
        String token = login("admin", "admin123");
        mvc.perform(get("/api/v1/cache/stats").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.redisCircuitState").exists());
    }

    /**
     * 验证点：刷新旋转——旧 refreshToken 刷新一次成功后立即作废，
     * 第二次用同一旧 token 刷新 → 401。
     */
    @Test
    void refreshRotationInvalidatesOldRefreshToken() throws Exception {
        String refreshToken = loginAndGetRefresh("st001", "123456");

        // 第一次刷新：成功，返回新 Token 对
        MvcResult first = mvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();

        // 第二次刷新：旧 refreshToken 已作废（旋转）→ 401
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 验证点：登出后 access 进入黑名单（旧 token 再访问 → 401），
     * 且 refreshToken 一并吊销（登出后再刷新 → 401）。
     */
    @Test
    void logoutBlacklistsAccessTokenAndRevokesRefresh() throws Exception {
        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"st002\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn();
        String accessToken = extract(login, "/data/accessToken");
        String refreshToken = extract(login, "/data/refreshToken");

        // 登出（Bearer + refreshToken 吊销）
        mvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk());

        // 旧 access 被黑名单拒绝
        mvc.perform(get("/api/v1/courses/1").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());

        // refreshToken 已被吊销，无法刷新
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 登录并返回 accessToken。
     */
    private String login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return extract(result, "/data/accessToken");
    }

    /**
     * 登录并返回 refreshToken。
     */
    private String loginAndGetRefresh(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return extract(result, "/data/refreshToken");
    }

    /**
     * 从 R 包装响应按 JSON Pointer 提取字符串字段。
     */
    private String extract(MvcResult result, String pointer) throws Exception {
        return JsonUtil.mapper().readTree(result.getResponse().getContentAsString())
                .at(pointer.replace("$", "").replace('.', '/')).asText();
    }
}
