package com.zjjxy.gateway.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 用户表（课堂版：内存 + BCrypt 哈希）。
 * <p>
 * ★ 为什么存哈希而不是明文：哪怕只是 demo，也别把"明文密码"这个习惯带进代码里。
 *   BCrypt 自带盐、计算慢，泄露后也很难反查。
 * <p>
 * ★ 为什么不存注册：企业知识库的账号应该来自 HR/AD/LDAP/SSO，
 *   自注册等于允许任何人进内网知识库。这里只放三个种子账号，
 *   真实项目把 authenticate() 换成 LDAP/SSO 校验即可，接口不变。
 */
@Component
public class UserStore {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    /** 对外暴露的身份（不含密码哈希） */
    public record Principal(String username, String role, long tenantId) {
    }

    /** 内部存储：密码哈希 + 身份。private，避免外部拿到哈希 */
    private record Account(String passwordHash, String role, long tenantId) {
    }

    private final Map<String, Account> accounts = Map.of(
            "admin",  new Account(ENCODER.encode("admin123"),  "admin",  1001L),
            "editor", new Account(ENCODER.encode("editor123"), "editor", 1001L),
            "guest",  new Account(ENCODER.encode("guest123"),  "guest",  1001L)
    );

    /** 校验用户名密码；失败返回 empty，由调用方转 401 */
    public Optional<Principal> authenticate(String username, String password) {
        Account a = accounts.get(username);
        if (a == null || !ENCODER.matches(password, a.passwordHash())) {
            return Optional.empty();
        }
        return Optional.of(new Principal(username, a.role(), a.tenantId()));
    }
}