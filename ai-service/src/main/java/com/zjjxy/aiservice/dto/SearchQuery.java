package com.zjjxy.aiservice.dto;

/** 发给 search-service 的检索请求 */
public record SearchQuery(String query, Long tenantId, Integer topK) {
}