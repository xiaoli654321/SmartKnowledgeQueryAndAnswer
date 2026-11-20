package com.zjjxy.searchservice.dto;

import lombok.Data;

/**
 * 关键词检索的一条结果（MySQL FULLTEXT 打分）。
 * score 是 MySQL 的 MATCH...AGAINST 相关度，数值本身没有绝对意义，只用来排名。
 */
@Data
public class KeywordHit {
    private Long id;
    private Long documentId;
    private Long tenantId;
    private Integer chunkIndex;
    private String content;
    private Double score;
}