package com.zjjxy.doc_service.rbac;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级权限注解（定义已给全，这是"契约"不是"逻辑"）。
 * <p>
 * 【注解思路】
 * - @Target(METHOD)：只能标在方法上，别让它标在类上造成歧义；
 * - @Retention(RUNTIME)：运行时切面要能读到它，CLASS 就失效了；
 * - value() 用字符串权限点（document:write / document:read），
 *   而不是枚举 —— 以后加权限不用改注解类、不用重新编译调用方。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {
    /** 需要的权限点，例如 document:write */
    String value();
}
