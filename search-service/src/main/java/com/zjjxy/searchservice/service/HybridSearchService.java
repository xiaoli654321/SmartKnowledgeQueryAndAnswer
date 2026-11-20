package com.zjjxy.searchservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjjxy.searchservice.client.EmbeddingClient;
import com.zjjxy.searchservice.dto.KeywordHit;
import com.zjjxy.searchservice.dto.SearchHit;
import com.zjjxy.searchservice.dto.SearchRequest;
import com.zjjxy.searchservice.entity.DocChunk;
import com.zjjxy.searchservice.mapper.DocChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索：关键词 + 向量，两路融合。
 * <p>
 * 为什么必须两路：
 * - 纯关键词：查"年假"能命中，但用户问"休假能休几天"就漏（字面不重合）；
 * - 纯向量：语义能对上，但对"年假 5 天"这种精确数字、专有名词反而不稳。
 * <p>
 * 融合为什么用 RRF（Reciprocal Rank Fusion）而不是加权求和：
 * 关键词分和余弦分【量纲完全不同】（MySQL 相关度可能是几十，余弦在 0~1），
 * 直接相加会让分数大的那一路把另一路压死。RRF 只看各自排名，与分数量纲无关：
 * <pre>
 *   RRF(doc) = Σ 1 / (k + rank_i)      k 通常取 60
 * </pre>
 * 两路都靠前的块得分自然高，只被一路命中的也仍有机会进结果 —— 这是它比"硬过滤"稳的地方。
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private final DocChunkMapper chunkMapper;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;

    @Value("${search.top-k:5}")
    private int topK;
    @Value("${search.keyword-top-k:10}")
    private int keywordTopK;
    @Value("${search.vector-top-k:10}")
    private int vectorTopK;
    @Value("${search.rrf-k:60}")
    private int rrfK;
    @Value("${search.vector-candidate-limit:2000}")
    private int candidateLimit;

    public HybridSearchService(DocChunkMapper chunkMapper,
                               EmbeddingClient embeddingClient,
                               ObjectMapper objectMapper) {
        this.chunkMapper = chunkMapper;
        this.embeddingClient = embeddingClient;
        this.objectMapper = objectMapper;
    }

    public List<SearchHit> hybrid(SearchRequest request) {
        String query = request.query();
        Long tenantId = request.tenantId();
        int limit = (request.topK() == null || request.topK() <= 0) ? topK : request.topK();

        long t0 = System.currentTimeMillis();
        List<KeywordHit> keywordHits = chunkMapper.keywordSearch(query, tenantId, keywordTopK);
        long t1 = System.currentTimeMillis();
        List<Scored> vectorHits = vectorSearch(query, tenantId);
        long t2 = System.currentTimeMillis();

        log.info("召回完成 query=[{}] 关键词={}条({}ms) 向量={}条({}ms)",
                query, keywordHits.size(), t1 - t0, vectorHits.size(), t2 - t1);

        return fuse(keywordHits, vectorHits, limit);
    }

    /** 一路召回结果：分块 + 余弦相似度 */
    private record Scored(DocChunk chunk, double similarity) {
    }

    /**
     * 向量召回：算 query 向量 → 与候选逐条余弦 → 取 topN。
     * 向量服务挂了只记日志、返回空列表，让检索退化成"仅关键词"，而不是整个接口 500。
     */
    private List<Scored> vectorSearch(String query, Long tenantId) {
        float[] queryVec;
        try {
            queryVec = embeddingClient.embedOne(query);
        } catch (Exception e) {
            log.error("query 向量计算失败，本次仅关键词检索：{}", e.getMessage());
            return List.of();
        }

        List<DocChunk> candidates = chunkMapper.vectorCandidates(tenantId, candidateLimit);
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (DocChunk c : candidates) {
            float[] vec = parseVector(c.getEmbedding());
            if (vec == null || vec.length != queryVec.length) continue;
            scored.add(new Scored(c, cosine(queryVec, vec)));
        }
        scored.sort(Comparator.comparingDouble(Scored::similarity).reversed());
        return scored.subList(0, Math.min(vectorTopK, scored.size()));
    }

    /** 余弦相似度：dot / (|a| * |b|)，范围 -1~1，这里基本都是 0~1 */
    private double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** LONGTEXT 里的 JSON 数组文本 -> float[] */
    private float[] parseVector(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, float[].class);
        } catch (Exception e) {
            log.warn("向量解析失败，跳过该块：{}", e.getMessage());
            return null;
        }
    }

    /** RRF 融合：按 chunkId 合并两路排名，加权求和后取前 limit 条 */
    private List<SearchHit> fuse(List<KeywordHit> keywordHits, List<Scored> vectorHits, int limit) {
        Map<Long, Merge> merged = new LinkedHashMap<>();

        for (int i = 0; i < keywordHits.size(); i++) {
            KeywordHit k = keywordHits.get(i);
            Merge m = merged.computeIfAbsent(k.getId(),
                    id -> new Merge(id, k.getDocumentId(), k.getChunkIndex(), k.getContent()));
            m.keywordRank = i + 1;
        }
        for (int i = 0; i < vectorHits.size(); i++) {
            DocChunk v = vectorHits.get(i).chunk();
            Merge m = merged.computeIfAbsent(v.getId(),
                    id -> new Merge(id, v.getDocumentId(), v.getChunkIndex(), v.getContent()));
            m.vectorRank = i + 1;
            m.similarity = vectorHits.get(i).similarity();
        }

        List<Merge> list = new ArrayList<>(merged.values());
        for (Merge m : list) {
            double s = 0;
            if (m.keywordRank != null) s += 1.0 / (rrfK + m.keywordRank);
            if (m.vectorRank != null) s += 1.0 / (rrfK + m.vectorRank);
            m.score = s;
        }
        list.sort(Comparator.comparingDouble((Merge m) -> m.score).reversed());

        List<SearchHit> out = new ArrayList<>(limit);
        for (int i = 0; i < Math.min(limit, list.size()); i++) {
            Merge m = list.get(i);
            out.add(new SearchHit(m.chunkId, m.documentId, m.chunkIndex, m.content,
                    Math.round(m.score * 1e6) / 1e6, m.keywordRank, m.vectorRank));
        }
        return out;
    }

    /** 融合用的临时结构 */
    private static class Merge {
        final Long chunkId;
        final Long documentId;
        final Integer chunkIndex;
        final String content;
        Integer keywordRank;
        Integer vectorRank;
        double similarity;
        double score;

        Merge(Long chunkId, Long documentId, Integer chunkIndex, String content) {
            this.chunkId = chunkId;
            this.documentId = documentId;
            this.chunkIndex = chunkIndex;
            this.content = content;
        }
    }
}