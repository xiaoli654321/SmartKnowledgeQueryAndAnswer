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
 * 权限切面：拦截所有带 @RequirePermission 的方法，按"角色 → 权限点"做判定。
 * <p>
 * 【注解思路】
 * - @Aspect + @Component：Spring AOP 靠它生效（doc-service 已有 spring-boot-starter-aop）；
 * - @Around("@annotation(requirePermission)")：把注解对象本身当参数拿进来，
 *   这样能直接读 requirePermission.value()，不用反射再去解析方法签名。
 * <p>
 * 【环绕通知的两条铁律】
 * 1) 必须调用 pjp.proceed()，否则被拦截的业务方法根本不会执行 ——
 *    接口会直接返回你这个方法的返回值，看起来"没报错但什么都没干"；
 * 2) 必须 return pjp.proceed() 的结果，那是业务方法的真实返回值
 *    （这里是 UploadResult / KbDocument），不能拿别的对象顶替。
 * <p>
 * 课堂版把权限表写死在内存里；生产上这张表应该在库或配置中心，
 * 否则"改一个权限要重新发版"是不可接受的。
 */
@Aspect
@Component
public class PermissionAspect {

    private static final Logger log = LoggerFactory.getLogger(PermissionAspect.class);

    /**
     * 角色 → 权限点集合。
     * <p>
     * 用 Set.of() 是因为这张表只读：任何人都（包括将来的自己）都不该在运行时改它。
     */
    private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
            "admin", Set.of("document:read", "document:write", "document:delete"),
            "editor", Set.of("document:read", "document:write"),
            "guest", Set.of("document:read")
    );

    @Around("@annotation(requirePermission)")
    public Object around(ProceedingJoinPoint pjp, RequirePermission requirePermission) throws Throwable {
        String need = requirePermission.value();   // 这个方法需要什么权限，例如 document:write
        String role = currentRole();               // 当前请求的角色，没带请求头就是 guest

        // getOrDefault + Set.of()：角色名写错（比如 "Admin"）时不会 NPE，
        // 而是得到空权限集合，最终走 403 —— 出错时倾向于拒绝，而不是放行
        Set<String> owned = ROLE_PERMISSIONS.getOrDefault(role, Set.of());

        if (!owned.contains(need)) {
            log.warn("权限不足 role={} need={} method={}", role, need, pjp.getSignature().toShortString());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "无权限执行该操作（当前角色：" + role + "，需要：" + need + "）");
        }

        return pjp.proceed();   // ★ 放行：执行真正的业务方法并原样返回结果
    }

    /**
     * 取当前请求的角色（这个已经写好了，可以直接用）。
     * <p>
     * - 请求头 X-User-Role 是课堂版的"身份传递"，真实项目里应该是网关注入的 JWT 解析结果；
     * - 非 Web 线程（@Async、定时任务）没有请求上下文，返回 guest（最小权限）。
     */
    private String currentRole() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "guest";
        }
        String role = attrs.getRequest().getHeader("X-User-Role");
        if (role == null || role.isBlank()) {
            return "guest";
        }
        return role.trim().toLowerCase();
    }
}
