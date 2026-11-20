package com.zjjxy.doc_service.rbac;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;

/**
 * 权限切面：拦截所有带 @RequirePermission 的方法。
 * <p>
 * 角色 → 权限点先写死在内存里（课堂版）。生产上这张表应该在库里或配置中心，
 * 因为"改权限要重新发版"是不可接受的。
 * <p>
 * M5 网关还会再做一次统一鉴权，这里保留是"纵深防御"：
 * 绕过网关直连 8082 时，这一层仍然拦得住。
 */
@Aspect
@Component
public class PermissionAspect {

    private static final Logger log = LoggerFactory.getLogger(PermissionAspect.class);

    /** 角色 -> 权限点集合 */
    private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
            "admin", Set.of("document:read", "document:write", "document:delete"),
            "editor", Set.of("document:read", "document:write"),
            "guest", Set.of("document:read")
    );

    @Around("@annotation(requirePermission)")
    public Object around(ProceedingJoinPoint pjp, RequirePermission requirePermission) throws Throwable {
        String need = requirePermission.value();
        String role = currentRole();
        Set<String> owned = ROLE_PERMISSIONS.getOrDefault(role, Set.of());

        if (!owned.contains(need)) {
            log.warn("权限不足 role={} need={} method={}", role, need, pjp.getSignature().toShortString());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "无权限：" + need + "（当前角色：" + role + "）");
        }
        return pjp.proceed();
    }

    /** 从请求头 X-User-Role 取角色；没有就按最小权限 guest 处理 */
    private String currentRole() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "guest";      // 非 Web 线程（异步/定时任务）默认最小权限
        }
        String role = attrs.getRequest().getHeader("X-User-Role");
        if (role == null || role.isBlank()) {
            return "guest";
        }
        return role.trim().toLowerCase();
    }
}