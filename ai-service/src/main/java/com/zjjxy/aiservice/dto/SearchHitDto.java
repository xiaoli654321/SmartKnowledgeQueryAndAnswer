package com.zjjxy.aiservice.dto;

/**
 * 检索服务返回的一条命中（search-service 的 SearchHit 镜像，字段名必须一致）。
 * score 是 RRF 融合分，不是余弦相似度。
 */
public record SearchHitDto(
        Long chunkId,
        Long documentId,
        Integer chunkIndex,
        String content,
        double score,
        Integer keywordRank,
        Integer vectorRank
) {
}