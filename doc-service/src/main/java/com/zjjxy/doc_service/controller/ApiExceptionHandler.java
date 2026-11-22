package com.zjjxy.doc_service.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.Map;

/**
 * 统一异常出口（固定写法，不用你写逻辑）。
 * <p>
 * 作用：把"用户能看懂的错误"翻成 400，而不是一律 500。
 * - IllegalArgumentException（参数/不支持的文件类型）→ 400
 * - IOException（文件损坏/加密）→ 400
 * <p>
 * 注意：ResponseStatusException（404 不存在、403 无权限）由 Spring 自己处理，
 * 这里**不要**再拦，否则状态码会被改成 500。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        log.warn("请求参数错误：{}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> parseFailed(IOException e) {
        log.warn("文件解析失败：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
