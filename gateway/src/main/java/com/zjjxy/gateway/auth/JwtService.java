package com.zjjxy.gateway.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * JWT 签发与校验。
 * <p>
 * 三个关键点：
 * 1) ★ 密钥必须来自环境变量，不能写死在代码里（写死 = 任何人都能伪造 token）；
 *    长度不足 32 字节会直接抛 WeakKeyException —— 这是 jjwt 的保护，别绕过它。
 * 2) role 放进 claim，网关校验后**用它覆盖**请求头 X-User-Role，
 *    这样前端传什么都无所谓，伪造不了。
 * 3) 校验失败一律返回 empty，由调用方决定 401 还是当 guest。
 */
@Component
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(@Value("${security.jwt.secret}") String secret,
                      @Value("${security.jwt.ttl-seconds:7200}") long ttlSeconds) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("security.jwt.secret 至少需要 32 字节（当前 " + bytes.length + "）");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(String username, String role, long tenantId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .claim("tenantId", tenantId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    /** 校验并返回 claims；无效/过期/算法不符都返回 empty */
    public Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("token 校验失败：{}", e.getMessage());
            return Optional.empty();
        }
    }
}