package com.zjjxy.gateway.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 登录端点（网关自己提供，不单独起认证服务）。
 * <p>
 * 为什么放在网关：入口只有一个，签发和校验在同一处，不用再引一个服务；
 * 真实项目里鉴权往往独立成 auth-service，但对本项目 3 个种子账号够用。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserStore userStore;
    private final JwtService jwtService;

    public AuthController(UserStore userStore, JwtService jwtService) {
        this.userStore = userStore;
        this.jwtService = jwtService;
    }

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String token, String username, String role, long tenantId, long expiresIn) {
    }

    @PostMapping("/login")
    public Mono<LoginResponse> login(@RequestBody LoginRequest req) {
        if (req == null || req.username() == null || req.password() == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名和密码不能为空"));
        }
        return Mono.justOrEmpty(userStore.authenticate(req.username(), req.password()))
                .map(a -> {
                    String token = jwtService.issue(req.username(), a.role(), a.tenantId());
                    log.info("登录成功 user={} role={}", req.username(), a.role());
                    return new LoginResponse(token, req.username(), a.role(), a.tenantId(), jwtService.ttlSeconds());
                })
                .switchIfEmpty(Mono.error(
                        new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误")));
    }

    /** 校验当前 token 并返回身份（前端刷新页面时用来恢复登录态） */
    @GetMapping("/me")
    public Mono<LoginResponse> me(@RequestHeader(value = "Authorization", required = false) String auth) {
        String token = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7) : null;
        if (token == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录"));
        }
        return Mono.justOrEmpty(jwtService.parse(token))
                .map(c -> new LoginResponse(token, c.getSubject(),
                        String.valueOf(c.get("role")), ((Number) c.get("tenantId")).longValue(),
                        jwtService.ttlSeconds()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "token 无效或已过期")));
    }
}