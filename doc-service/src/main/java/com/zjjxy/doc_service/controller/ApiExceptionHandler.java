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
 * 统一异常出口：把"用户能看懂的错误"转成 400，而不是一律 500。
 * <p>
 * 说明：ResponseStatusException（404 找不到、403 无权限）由 Spring 自己处理，
 * 这里不拦截，否则会把状态码改成 500。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 参数/文件类型问题 -> 400 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        log.warn("请求参数错误：{}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    /** 文件解析失败 -> 400（文件是用户给的，不是服务器错） */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> parseFailed(IOException e) {
        log.warn("文件解析失败：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", String.valueOf(e.getMessage())));
    }
}