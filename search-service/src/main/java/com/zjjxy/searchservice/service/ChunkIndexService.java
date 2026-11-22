package com.zjjxy.searchservice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjjxy.searchservice.client.EmbeddingClient;
import com.zjjxy.searchservice.entity.DocChunk;
import com.zjjxy.searchservice.event.DocumentIndexedEvent;
import com.zjjxy.searchservice.mapper.DocChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 建索引：把一条「文档已索引」事件落成 doc_chunk 记录（含向量）。
 * <p>
 * ★ 幂等策略：先按 (document_id, tenant_id) 删旧块，再整批插入。
 *   为什么必须删：文档改版会重发事件，不删就会有新旧两份内容一起被检索到，
 *   用户可能拿到已经作废的政策条款 —— 这种错误比"搜不到"危险得多。
 * <p>
 * ★ 向量失败降级：embedding 调用失败时不阻断入库，只把 embedding 留空。
 *   这样关键词那一路仍然可用，等模型恢复后重新触发一次索引即可补齐。
 */
@Service
public class ChunkIndexService {

    private static final Logger log = LoggerFactory.getLogger(ChunkIndexService.class);

    private final DocChunkMapper chunkMapper;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;

    @Value("${embedding.model}")
    private String embeddingModel;

    public ChunkIndexService(DocChunkMapper chunkMapper,
                             EmbeddingClient embeddingClient,
                             ObjectMapper objectMapper) {
        this.chunkMapper = chunkMapper;
        this.embeddingClient = embeddingClient;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void index(DocumentIndexedEvent event) {
        List<DocumentIndexedEvent.Chunk> chunks = event.chunks();
        if (chunks == null || chunks.isEmpty()) {
            log.warn("事件里没有分块，跳过 docId={}", event.docId());
            return;
        }

        // 1) 幂等：清掉这篇文档的旧块（含旧版本）
        int deleted = chunkMapper.delete(new QueryWrapper<DocChunk>()
                .eq("document_id", event.docId())
                .eq("tenant_id", event.tenantId()));
        if (deleted > 0) {
            log.info("清理旧索引 docId={} 删除 {} 块", event.docId(), deleted);
        }

        // 2) 批量算向量；失败降级为 null（只存关键词）
        List<String> contents = chunks.stream().map(DocumentIndexedEvent.Chunk::content).toList();
        List<float[]> vectors = tryEmbed(contents, event.docId());

        // 3) 逐条落库
        for (int i = 0; i < chunks.size(); i++) {
            DocumentIndexedEvent.Chunk c = chunks.get(i);
            DocChunk row = new DocChunk();
            row.setDocumentId(event.docId());
            row.setTenantId(event.tenantId());
            row.setChunkIndex(c.index());
            row.setContent(c.content());
            row.setEmbeddingModel(embeddingModel);
            if (vectors != null) {
                row.setEmbedding(toJson(vectors.get(i)));
            }
            chunkMapper.insert(row);
        }

        log.info("建索引完成 docId={} tenantId={} version={} chunks={} 向量={}",
                event.docId(), event.tenantId(), event.version(), chunks.size(),
                vectors != null ? "有" : "无(降级)");
    }

    /** 算向量；任何异常都降级成 null，绝不让"模型抖动"变成"文档建不进去" */
    private List<float[]> tryEmbed(List<String> contents, Long docId) {
        try {
            List<float[]> vectors = new ArrayList<>(contents.size());
            vectors.addAll(embeddingClient.embedAll(contents));
            return vectors;
        } catch (Exception e) {
            log.error("向量计算失败，降级为仅关键词索引 docId={} err={}", docId, e.getMessage());
            return null;
        }
    }

    /** float[] -> JSON 数组文本，存 LONGTEXT */
    private String toJson(float[] vec) {
        try {
            return objectMapper.writeValueAsString(vec);
        } catch (Exception e) {
            log.error("向量序列化失败", e);
            return null;
        }
    }
}