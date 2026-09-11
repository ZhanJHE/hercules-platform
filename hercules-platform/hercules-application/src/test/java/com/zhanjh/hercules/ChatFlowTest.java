package com.zhanjh.hercules;

import com.zhanjh.hercules.agent.core.StubLlmPort;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对话流集成测试（阶段 D）：H2 + 内存桩（L2/认证存储/LLM），验证 chat SSE 端点的
 * 认证、意图路由到编排链的贯通，以及确认制的安全边界。
 *
 * <p>状态无关设计：用例全部以 admin 身份执行（无 studentId，执行侧必然拒绝），
 * 断言不依赖冒烟测试遗留的选课状态；LLM 响应由 StubLlmPort 脚本化，零网络。
 * 会话归属用例额外以 st001 登录，但其请求在归属校验处即被拒绝，不会触达选课状态。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestStubsConfig.class)
class ChatFlowTest {

    /** MockMvc：模拟 HTTP 请求（含 Security 过滤器链）。 */
    @Autowired
    private MockMvc mvc;

    /** LLM 脚本化桩（@Primary 注入编排链）。 */
    @Autowired
    private StubLlmPort llm;

    /**
     * 以演示账号登录并返回 accessToken。
     */
    private String login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonUtil.mapper().readTree(result.getResponse().getContentAsString())
                .at("/data/accessToken").asText();
    }

    /**
     * 验证点：未登录调用对话端点 → 401（chat/** 要求认证）。
     */
    @Test
    void unauthenticatedChatIsRejected() throws Exception {
        mvc.perform(post("/api/v1/chat")
                        .contentType("application/json")
                        .content("{\"sessionId\":\"s\",\"message\":\"推荐几门课\"}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 验证点：推荐链贯通——admin 登录 → SSE 200（text/event-stream）→
     * token 事件携带 LLM 桩响应全文 → meta 事件携带候选结构化数据。
     */
    @Test
    void recommendChainStreamsOverSse() throws Exception {
        llm.enqueue("推荐《程序设计基础》，专业基础课，建议优先选择。");
        String admin = login("admin", "admin123");

        MvcResult async = mvc.perform(post("/api/v1/chat")
                        .header("Authorization", "Bearer " + admin)
                        .contentType("application/json")
                        .content("{\"sessionId\":\"chat-s1\",\"message\":\"推荐几门课\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult dispatched = mvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andReturn();
        String sse = dispatched.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(sse).contains("event:token");
        // token 分片会把中文切开，解析全部 data 行拼接后再断言全文
        String joined = java.util.Arrays.stream(sse.split("\n"))
                .filter(l -> l.startsWith("data:"))
                .map(l -> l.substring(5))
                .collect(java.util.stream.Collectors.joining());
        assertThat(joined).contains("推荐《程序设计基础》");
        assertThat(sse).contains("event:meta").contains("candidateCount");
    }

    /**
     * 验证点：选课链贯通——admin 发起选课请求 → 冲突校验通过 → 进入待确认（文案含确认指引）；
     * 随后确认 → 执行侧拒绝非学生角色（安全边界：写操作仅 STUDENT）。
     */
    @Test
    void enrollChainWaitsForConfirmationAndRejectsNonStudentExecution() throws Exception {
        String admin = login("admin", "admin123");

        MvcResult enrollAsync = mvc.perform(post("/api/v1/chat")
                        .header("Authorization", "Bearer " + admin)
                        .contentType("application/json")
                        .content("{\"sessionId\":\"chat-s2\",\"message\":\"帮我选 CS101\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String enrollSse = mvc.perform(asyncDispatch(enrollAsync))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(enrollSse).as(enrollSse).contains("通过冲突校验").contains("回复「确认」");

        MvcResult confirmAsync = mvc.perform(post("/api/v1/chat")
                        .header("Authorization", "Bearer " + admin)
                        .contentType("application/json")
                        .content("{\"sessionId\":\"chat-s2\",\"message\":\"确认\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String confirmSse = mvc.perform(asyncDispatch(confirmAsync))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(confirmSse).as(confirmSse).contains("仅学生角色可以执行选课操作");
    }

    /**
     * 验证点：会话历史端点返回内存会话的发言序列（一轮 = user + assistant 两条）。
     */
    @Test
    void historyEndpointReturnsConversation() throws Exception {
        String admin = login("admin", "admin123");
        llm.enqueue("历史链路推荐文本");
        MvcResult async = mvc.perform(post("/api/v1/chat")
                        .header("Authorization", "Bearer " + admin)
                        .contentType("application/json")
                        .content("{\"sessionId\":\"chat-s3\",\"message\":\"推荐几门课\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(async)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/chat/history/chat-s3").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].role").value("user"))
                .andExpect(jsonPath("$.data[0].content").value("推荐几门课"))
                .andExpect(jsonPath("$.data[1].role").value("assistant"))
                .andExpect(jsonPath("$.data[1].content").value("历史链路推荐文本"));
    }

    /**
     * 验证点（会话归属）：同一用户复用同一 sessionId 不被归属校验拦截（回归保护）。
     */
    @Test
    void sameUserCanReuseSession() throws Exception {
        String admin = login("admin", "admin123");
        llm.enqueue("第一次回复", "第二次回复");

        MvcResult first = mvc.perform(post("/api/v1/chat")
                        .header("Authorization", "Bearer " + admin)
                        .contentType("application/json")
                        .content("{\"sessionId\":\"chat-s4\",\"message\":\"推荐几门课\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(first)).andExpect(status().isOk());

        // 复用同一 sessionId：归属一致，第二次仍正常受理并流式返回
        MvcResult second = mvc.perform(post("/api/v1/chat")
                        .header("Authorization", "Bearer " + admin)
                        .contentType("application/json")
                        .content("{\"sessionId\":\"chat-s4\",\"message\":\"再推荐几门课\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(second)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/chat/history/chat-s4").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    /**
     * 验证点（会话越权）：他人以同一 sessionId 发起对话 → 403（同步拒绝，不进入编排链，
     * 因此也不会劫持该会话下已登记的待确认选课）。
     */
    @Test
    void otherUserCannotPostToOwnedSession() throws Exception {
        String admin = login("admin", "admin123");
        String student = login("st001", "123456");
        llm.enqueue("管理员会话回复");

        MvcResult async = mvc.perform(post("/api/v1/chat")
                        .header("Authorization", "Bearer " + admin)
                        .contentType("application/json")
                        .content("{\"sessionId\":\"chat-s5\",\"message\":\"推荐几门课\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(async)).andExpect(status().isOk());

        // st001 复用 admin 的会话：归属不一致 → 403（该消息不会触达选课校验）
        mvc.perform(post("/api/v1/chat")
                        .header("Authorization", "Bearer " + student)
                        .contentType("application/json")
                        .content("{\"sessionId\":\"chat-s5\",\"message\":\"帮我选 CS101\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    /**
     * 验证点（会话越权）：他人读取会话历史 → 403；未知 sessionId 返回空历史而非 403
     * （只读判定不登记新会话，随机 sessionId 探测无副作用）。
     */
    @Test
    void otherUserCannotReadOwnedSessionHistory() throws Exception {
        String admin = login("admin", "admin123");
        String student = login("st001", "123456");
        llm.enqueue("会话归属校验用文本");

        MvcResult async = mvc.perform(post("/api/v1/chat")
                        .header("Authorization", "Bearer " + admin)
                        .contentType("application/json")
                        .content("{\"sessionId\":\"chat-s6\",\"message\":\"推荐几门课\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(async)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/chat/history/chat-s6").header("Authorization", "Bearer " + student))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mvc.perform(get("/api/v1/chat/history/chat-never-used").header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}

