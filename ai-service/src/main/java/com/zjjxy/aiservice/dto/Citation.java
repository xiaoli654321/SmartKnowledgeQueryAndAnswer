package com.zjjxy.aiservice.dto;

/**
 * 出处（契约，给全）：回答里的 [n] 就是这里的 index。
 * <p>
 * 这是 RAG 与"直接问大模型"的分水岭 —— 每个结论都能点回原文。
 * snippet 只截前 160 字，前端展示够用，也避免响应体过大。
 */
public record Citation(
        int index,
        Long documentId,
        Integer chunkIndex,
        String snippet,
        double score
) {
}