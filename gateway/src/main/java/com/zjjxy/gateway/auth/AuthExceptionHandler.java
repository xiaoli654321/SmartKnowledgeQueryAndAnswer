package com.zjjxy.gateway.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 把 ResponseStatusException 转成 {"error":"..."} 结构。
 * <p>
 * 为什么需要：Spring 默认的 401/403 响应体不含 message（只返回 "Unauthorized"），
 * 前端拿不到"用户名或密码错误"这种可读信息，只能提示一个英文单词。
 * 这里只处理网关自己的异常（登录、/auth/me），不影响转发给下游的错误。
 */
@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<Map<String, String>>> handle(ResponseStatusException ex) {
        String message = ex.getReason() != null ? ex.getReason() : "请求失败";
        return Mono.just(ResponseEntity.status(ex.getStatusCode()).body(Map.of("error", message)));
    }
}