package com.zhanjh.hercules.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与解析工具（HS256，jjwt 0.13.0）。
 *
 * <p>规范（《认证设计.md》）：secret ≥ 256 位且来自配置（gitignore）；payload 仅
 * userId/username/role/studentId 业务字段 + jti/exp 标准字段，禁止敏感信息；
 * jti 用于登出黑名单，exp 用于过期控制。
 *
 * <p>线程安全性：SecretKey 不可变，可并发调用。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public class JwtUtil {

    /** 签名密钥（HMAC-SHA256）。 */
    private final SecretKey key;

    /** Access Token 有效期（毫秒）。 */
    private final long accessTtlMillis;

    /**
     * 构造工具。
     *
     * @param secret           签名密钥明文，UTF-8 编码后须 ≥ 32 字节（256 位），否则拒绝启动
     * @param accessTtlMillis  Access Token 有效期（毫秒）
     */
    public JwtUtil(String secret, long accessTtlMillis) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("hercules.auth.secret 未配置或长度不足 256 位（至少 32 字节）");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlMillis = accessTtlMillis;
    }

    /**
     * 已解析的 Access Token 声明（同时作为 SecurityContext 的认证主体）。
     *
     * @param userId           用户主键（t_user.id）
     * @param username         登录名
     * @param role             角色（STUDENT/ADMIN）
     * @param studentId        学生业务号（管理员为 null）
     * @param jti              JWT 唯一 ID（登出黑名单键）
     * @param expiresAtMillis  过期时间戳（epoch millis）
     */
    public record AuthClaims(long userId, String username, String role, Long studentId,
                             String jti, long expiresAtMillis) {
    }

    /**
     * 签发 Access Token。
     *
     * @param userId    用户主键
     * @param username  登录名
     * @param role      角色
     * @param studentId 学生业务号（可为 null，管理员场景）
     * @return 紧凑型 JWT 字符串
     */
    public String createAccessToken(long userId, String username, String role, Long studentId) {
        long now = System.currentTimeMillis();
        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date(now))
                .expiration(new Date(now + accessTtlMillis));
        if (studentId != null) {
            builder.claim("studentId", studentId);
        }
        return builder.signWith(key).compact();
    }

    /**
     * 解析并校验 Access Token（签名 + 过期）。
     *
     * @param token JWT 字符串
     * @return 认证声明
     * @throws io.jsonwebtoken.JwtException 签名无效/格式错误/已过期时抛出（由过滤器转 401）
     */
    public AuthClaims parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        Number studentId = claims.get("studentId", Number.class);
        return new AuthClaims(
                Long.parseLong(claims.getSubject()),
                claims.get("username", String.class),
                claims.get("role", String.class),
                studentId == null ? null : studentId.longValue(),
                claims.getId(),
                claims.getExpiration().getTime());
    }

    /**
     * 读取 Access Token 有效期。
     *
     * @return 有效期毫秒数
     */
    public long accessTtlMillis() {
        return accessTtlMillis;
    }
}
