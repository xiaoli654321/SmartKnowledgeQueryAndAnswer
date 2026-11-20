package com.zjjxy.searchservice.dto;

/**
 * 混合检索请求。
 * query    —— 用户问题（口语）
 * tenantId —— 必须传，检索只能在本租户范围内
 * topK     —— 可选，不传用配置里的默认值
 */
public record SearchRequest(String query, Long tenantId, Integer topK) {
}