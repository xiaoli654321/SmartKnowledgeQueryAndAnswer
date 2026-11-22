package com.zjjxy.aiservice.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一异常出口：参数问题 400。
 * ResponseStatusException（429 限流 / 503 熔断）由 Spring 自己处理，这里不拦。
 * <p>
 * （固定写法，不用你改）
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        log.warn("请求参数错误：{}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }
}