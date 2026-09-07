package com.zhanjh.hercules.controller;

import com.zhanjh.hercules.auth.AuthProperties;
import com.zhanjh.hercules.auth.AuthStorePort;
import com.zhanjh.hercules.auth.JwtUtil;
import com.zhanjh.hercules.common.BusinessException;
import com.zhanjh.hercules.common.R;
import com.zhanjh.hercules.mapper.UserMapper;
import com.zhanjh.hercules.model.User;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 认证接口：/api/v1/auth —— 登录 / 刷新 / 登出（白名单 + 双 Token 语义，见《认证设计.md》）。
 *
 * <p>接口语义：
 * <ul>
 *   <li>POST /login：用户名密码换 {accessToken, refreshToken}；</li>
 *   <li>POST /refresh：refreshToken 旋转换取新 Token 对（旧 refresh 立即作废）；</li>
 *   <li>POST /logout：当前 access 的 jti 入黑名单（剩余有效期）+ 吊销 refreshToken。</li>
 * </ul>
 *
 * <p>错误语义：401 用户名或密码错误 / 刷新令牌无效；登出写黑名单失败 → 500 重试
 * （不允许「登出失败但 token 继续有效」）。
 *
 * <p>线程安全性：无实例状态。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /**
     * 认证审计专用 logger（logback 中名为 AUTH_AUDIT，独立落盘 auth-audit.log，保留 90 天）。
     * 事件：LOGIN_SUCCESS / LOGIN_FAILURE / LOGOUT / LOGOUT_FAILED。密码与 token 一律不落日志。
     */
    private static final Logger AUDIT = LoggerFactory.getLogger("AUTH_AUDIT");

    /**
     * 登录请求体。
     *
     * @param username 登录名，必填
     * @param password 明文密码，必填（BCrypt 比对，不落日志）
     */
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    /**
     * 刷新请求体。
     *
     * @param refreshToken 刷新令牌（UUID）
     */
    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    /**
     * 登出请求体（可选提供 refreshToken 一并吊销）。
     *
     * @param refreshToken 刷新令牌，可选
     */
    public record LogoutRequest(String refreshToken) {
    }

    /**
     * 登录/刷新响应体。
     *
     * @param accessToken     访问令牌（JWT，30min）
     * @param refreshToken    刷新令牌（UUID，7d）
     * @param expiresInSeconds access 有效期秒数（前端据此安排静默刷新）
     * @param role            角色（STUDENT/ADMIN）
     * @param studentId       学生业务号（管理员为 null）
     * @param username        登录名
     */
    public record TokenResponse(String accessToken, String refreshToken, long expiresInSeconds,
                                String role, Long studentId, String username) {
    }

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthStorePort authStore;
    private final AuthProperties authProps;

    /**
     * 构造注入。
     *
     * @param userMapper      用户表访问接口
     * @param passwordEncoder BCrypt 编码器（比对用）
     * @param jwtUtil         JWT 工具
     * @param authStore       认证存储（refresh/黑名单）
     * @param authProps       认证配置（TTL）
     */
    public AuthController(UserMapper userMapper,
                          PasswordEncoder passwordEncoder,
                          JwtUtil jwtUtil,
                          AuthStorePort authStore,
                          AuthProperties authProps) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.authStore = authStore;
        this.authProps = authProps;
    }

    /**
     * 登录：用户名密码换取双 Token。
     *
     * <p>请求示例：{@code POST /api/v1/auth/login}，body {@code {"username":"st001","password":"123456"}}。
     *
     * @param request 登录请求体
     * @param httpRequest HTTP 请求（取客户端 IP 供审计）
     * @return Token 对与用户信息
     * @throws BusinessException code=401 用户名或密码错误 / 账号已停用
     */
    @PostMapping("/login")
    public R<TokenResponse> login(@RequestBody @jakarta.validation.Valid LoginRequest request,
                                  HttpServletRequest httpRequest) {
        String ip = clientIp(httpRequest);
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("username", request.username()));
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            AUDIT.warn("[AUTH-AUDIT] LOGIN_FAILURE username={} ip={} reason=BAD_CREDENTIALS",
                    request.username(), ip);
            throw new BusinessException(401, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            AUDIT.warn("[AUTH-AUDIT] LOGIN_FAILURE username={} ip={} reason=DISABLED",
                    request.username(), ip);
            throw new BusinessException(401, "账号已停用");
        }
        TokenResponse response = issueTokens(user);
        AUDIT.info("[AUTH-AUDIT] LOGIN_SUCCESS username={} ip={} role={} studentId={}",
                user.getUsername(), ip, user.getRole(), user.getStudentId());
        return R.ok(response);
    }

    /**
     * 刷新：旋转式换取新 Token 对（旧 refreshToken 立即作废）。
     *
     * <p>实现要点：fail-closed —— refreshToken 不存在或 Redis 故障都按失败处理，
     * 提示用户重新登录（不牺牲安全性换可用性）。
     *
     * @param request 刷新请求体
     * @return 新 Token 对与用户信息
     * @throws BusinessException code=401 刷新令牌无效或已过期
     */
    @PostMapping("/refresh")
    public R<TokenResponse> refresh(@RequestBody @jakarta.validation.Valid RefreshRequest request) {
        Long userId = authStore.getRefreshUserId(request.refreshToken());
        if (userId == null) {
            throw new BusinessException(401, "刷新令牌无效或已过期");
        }
        User user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(401, "账号不存在或已停用");
        }
        // 旋转：旧 refreshToken 一次性作废
        authStore.deleteRefreshToken(request.refreshToken());
        return R.ok(issueTokens(user));
    }

    /**
     * 登出：当前 access 的 jti 入黑名单（TTL=剩余有效期）+ 可选吊销 refreshToken。
     *
     * <p>请求示例：{@code POST /api/v1/auth/logout}（Bearer accessToken），
     * body {@code {"refreshToken":"..."}}（可选）。
     *
     * @param principal   认证主体（JwtAuthenticationFilter 写入）
     * @param request     登出请求体，可为 null
     * @param httpRequest HTTP 请求（取客户端 IP 供审计）
     * @return 统一响应体
     * @throws BusinessException code=401 未携带有效 token；
     *                           code=500 黑名单写入失败（fail-closed，用户需重试登出）
     */
    @PostMapping("/logout")
    public R<Map<String, Object>> logout(@AuthenticationPrincipal JwtUtil.AuthClaims principal,
                                         @RequestBody(required = false) LogoutRequest request,
                                         HttpServletRequest httpRequest) {
        if (principal == null) {
            throw new BusinessException(401, "未登录");
        }
        String ip = clientIp(httpRequest);
        long remaining = principal.expiresAtMillis() - System.currentTimeMillis();
        if (remaining > 0) {
            // fail-closed：黑名单写入失败必须让用户重试，否则登出形同虚设
            try {
                authStore.blacklistJti(principal.jti(), Duration.ofMillis(remaining));
            } catch (RuntimeException e) {
                AUDIT.warn("[AUTH-AUDIT] LOGOUT_FAILED username={} ip={} reason=BLACKLIST_WRITE_FAILED",
                        principal.username(), ip);
                throw new BusinessException(500, "登出失败，请重试");
            }
        }
        if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
            authStore.deleteRefreshToken(request.refreshToken());
        }
        AUDIT.info("[AUTH-AUDIT] LOGOUT username={} ip={} jti={}", principal.username(), ip, principal.jti());
        return R.ok(Map.of("loggedOut", true));
    }

    /**
     * 提取客户端 IP（供审计）：网关注入的 X-Client-IP 优先，回退直连 remoteAddr。
     *
     * @param httpRequest HTTP 请求
     * @return 客户端 IP 字符串
     */
    private String clientIp(HttpServletRequest httpRequest) {
        String ip = httpRequest.getHeader("X-Client-IP");
        return ip == null || ip.isBlank() ? httpRequest.getRemoteAddr() : ip;
    }

    /**
     * 签发双 Token（登录与刷新共用）。
     *
     * @param user 用户实体（已校验启用状态）
     * @return Token 对与用户信息
     */
    private TokenResponse issueTokens(User user) {
        String accessToken = jwtUtil.createAccessToken(user.getId(), user.getUsername(), user.getRole(), user.getStudentId());
        String refreshToken = UUID.randomUUID().toString();
        authStore.saveRefreshToken(refreshToken, user.getId(), authProps.getRefreshTtl());
        return new TokenResponse(accessToken, refreshToken,
                authProps.getAccessTtl().toSeconds(), user.getRole(), user.getStudentId(), user.getUsername());
    }
}
