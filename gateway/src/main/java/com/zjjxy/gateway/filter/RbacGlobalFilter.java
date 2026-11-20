package com.zjjxy.gateway.filter;

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
import java.util.Set;

/**
 * 网关统一鉴权（RBAC）。
 * <p>
 * 为什么放在网关而不是每个服务里各写一遍：
 * 入口只有一个，鉴权只做一次；服务内部再校验是为了"绕过网关直连端口"时的纵深防御。
 * <p>
 * 规则：读请求（GET/HEAD/OPTIONS）放行；写请求（POST/PUT/DELETE...）必须 admin 或 editor。
 * 角色来自请求头 X-User-Role（真实项目里是 JWT 解析出来的，这里为了聚焦 RBAC 先简化）。
 * <p>
 * ★ 注意这是 WebFlux（响应式），不能用 Servlet 那套 HttpServletResponse；
 *   返回 403 要自己往 response 里写 body，不能 return 一个 ResponseEntity。
 * ★ /actuator/** 必须放行，否则 Prometheus 抓指标会被 403 挡掉，
 *   现象是"监控面板全空"，但服务本身是好的，很容易查错方向。
 */
@Component
public class RbacGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RbacGlobalFilter.class);

    /** 允许写操作的角色 */
    private static final Set<String> WRITE_ROLES = Set.of("admin", "editor");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 健康检查/指标端点放行（否则监控抓不到）
        if (path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }

        String method = request.getMethod().name();
        boolean isRead = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
        if (isRead) {
            return chain.filter(exchange);
        }

        String role = request.getHeaders().getFirst("X-User-Role");
        String normalized = (role == null || role.isBlank()) ? "guest" : role.trim().toLowerCase();

        if (!WRITE_ROLES.contains(normalized)) {
            log.warn("网关拦截写请求 path={} method={} role={}", path, method, normalized);
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] body = ("{\"error\":\"无权限执行该操作（当前角色：" + normalized + "）\"}")
                    .getBytes(StandardCharsets.UTF_8);
            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
        }

        log.debug("放行写请求 path={} role={}", path, normalized);
        return chain.filter(exchange);
    }

    /** 越小越先执行；-1 表示在路由转发之前先做鉴权 */
    @Override
    public int getOrder() {
        return -1;
    }
}