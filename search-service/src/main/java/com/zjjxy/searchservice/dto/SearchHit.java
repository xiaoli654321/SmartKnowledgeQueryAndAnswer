package com.zjjxy.searchservice.dto;

/**
 * 混合检索返回的一条结果。
 * score        —— RRF 融合分（越大越靠前），不是余弦相似度
 * keywordRank  —— 这一块在关键词路里排第几（null = 没被关键词召回）
 * vectorRank   —— 这一块在向量路里排第几（null = 没被向量召回）
 * <p>
 * 把两个名次一起返回，出问题时一眼就能看出"是关键词没召回，还是向量没召回"。
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