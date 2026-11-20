package com.zjjxy.searchservice.dto;

/**
 * 混合检索返回的一条结果。
 * <p>
 * score     —— RRF 融合分（越大越靠前），不是余弦相似度
 * keywordRank / vectorRank —— 这一块在两路里各排第几（null 表示这一路没召回）
 * 把两个名次一起返回，出问题时一眼能看出"是关键词没召回，还是向量没召回"。
 */
public record SearchHit(
        Long chunkId,
        Long documentId,
        Integer chunkIndex,
        String content,
        double score,
        Integer keywordRank,
        Integer vectorRank
) {
}