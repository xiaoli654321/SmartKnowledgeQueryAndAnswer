package com.zjjxy.gateway.filter;

import com.zjjxy.gateway.auth.JwtService;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

/**
 * 网关统一鉴权：JWT 校验 + RBAC。
 * <p>
 * 与旧版的区别（很重要）：
 * - 旧版信任请求头 X-User-Role，前端下拉框选 admin 就是 admin —— 等于没鉴权；
 * - 新版从 Authorization: Bearer 里解析 token，取出 role claim，
 *   然后 **覆盖**（而不是读取）X-User-Role 再转发给下游。
 *   客户端传什么头都会被丢弃，伪造不了。
 * <p>
 * 规则：
 * - /auth/login、/actuator/** 放行（登录本身不能要求登录）；
 * - 读请求（GET/HEAD/OPTIONS）+ 只读 POST（/ai/ask、/search/hybrid）放行，
 *   ★ 提问是 POST 但语义是读，这条白名单必须保留，否则前台不能问问题；
 * - 写请求必须携带有效 token，且角色是 admin / editor。
 * <p>
 * 状态码语义：
 * - 没带 token → 401（未登录）；
 * - 带了但角色不够 → 403（已登录但没权限）；
 * - token 无效/过期 → 401。
 */
@Component
public class RbacGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RbacGlobalFilter.class);

    private static final Set<String> WRITE_ROLES = Set.of("admin", "editor");
    private static final Set<String> READ_ONLY_POST = Set.of("/ai/ask", "/search/hybrid");
    private static final Set<String> PUBLIC_PATHS = Set.of("/auth/login");

    private final JwtService jwtService;

    public RbacGlobalFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        if (path.startsWith("/actuator") || PUBLIC_PATHS.contains(path)) {
            return chain.filter(exchange);
        }

        // 1) 解析 token（可选）：无效 token 与没 token 都当"未登录"，但后者不报错
        Optional<Claims> claims = parseToken(request);
        String role = claims.map(c -> String.valueOf(c.get("role"))).orElse("guest");
        boolean authenticated = claims.isPresent();

        // 2) ★ 覆盖身份头：下游服务只认这个头，而它只能由网关写入
        ServerHttpRequest mutated = request.mutate()
                .headers(h -> {
                    h.remove("X-User-Role");
                    h.set("X-User-Role", role);
                    if (claims.isPresent()) {
                        h.set("X-User-Id", claims.get().getSubject());
                    }
                })
                .build();

        String method = request.getMethod().name();
        boolean isRead = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
        boolean isReadOnlyPost = "POST".equals(method) && READ_ONLY_POST.contains(path);

        if (isRead || isReadOnlyPost) {
            return chain.filter(exchange.mutate().request(mutated).build());
        }

        // 3) 写操作：必须是持有有效 token 的 admin/editor
        if (!authenticated) {
            log.warn("未登录的写请求 path={} method={}", path, method);
            return reject(exchange, HttpStatus.UNAUTHORIZED, "未登录：请先获取 token（POST /auth/login）");
        }
        if (!WRITE_ROLES.contains(role)) {
            log.warn("权限不足 path={} method={} role={}", path, method, role);
            return reject(exchange, HttpStatus.FORBIDDEN, "无权限执行该操作（当前角色：" + role + "）");
        }

        log.debug("放行写请求 path={} role={}", path, role);
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private Optional<Claims> parseToken(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return Optional.empty();
        }
        return jwtService.parse(auth.substring(7));
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        return -1;
    }
}