package com.zjjxy.searchservice.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 远程向量模型客户端（SiliconFlow 的 BAAI/bge-m3，1024 维）。
 * <p>
 * 接口兼容 OpenAI 的 /v1/embeddings：
 * 请求 {"model":"...", "input":["文本1","文本2"]}
 * 响应 {"data":[{"index":0,"embedding":[...]}, ...]}
 * <p>
 * ★ 最关键的一点：响应必须按 index 归位后再使用。
 *   服务端不保证返回顺序与入参一致；一旦错位，向量和文本就对不上，
 *   检索结果会"莫名其妙地不准"，而且完全不报错 —— 这类 bug 极难定位。
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    /** 单次请求最多带多少条文本（太大容易超时/超限） */
    private static final int BATCH_SIZE = 16;

    private final RestTemplate restTemplate;

    @Value("${embedding.base-url}")
    private String baseUrl;
    @Value("${embedding.api-key}")
    private String apiKey;
    @Value("${embedding.model}")
    private String model;

    public EmbeddingClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 批量算向量，返回顺序与入参严格一致 */
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(texts.size(), i + BATCH_SIZE));
            result.addAll(embedBatch(batch));
        }
        return result;
    }

    /** 单条查询向量（检索时用）：复用批量逻辑，取第 0 条 */
    public float[] embedOne(String text) {
        List<float[]> vectors = embedAll(List.of(text));
        if (vectors.isEmpty()) {
            throw new IllegalStateException("embedding 返回为空");
        }
        return vectors.get(0);
    }

    /**
     * 真正发 HTTP 请求的一层。
     * 失败直接抛异常：降级策略（只用关键词）由上层 ChunkIndexService / HybridSearchService 决定，
     * 客户端不做"吞异常"这种越权的事。
     */
    private List<float[]> embedBatch(List<String> batch) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = Map.of(
                "model", model,
                "input", batch,
                "encoding_format", "float"
        );

        JsonNode resp = restTemplate.postForObject(
                baseUrl + "/embeddings", new HttpEntity<>(body, headers), JsonNode.class);

        if (resp == null || !resp.has("data")) {
            throw new IllegalStateException("embedding 响应异常：" + resp);
        }

        // 按 index 归位：先开一个和入参等长的数组，再按下标填
        float[][] ordered = new float[batch.size()][];
        for (JsonNode item : resp.path("data")) {
            int idx = item.path("index").asInt(-1);
            if (idx < 0 || idx >= ordered.length) {
                continue;
            }
            JsonNode vec = item.path("embedding");
            float[] arr = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) {
                arr[i] = (float) vec.get(i).asDouble();
            }
            ordered[idx] = arr;
        }

        List<float[]> out = new ArrayList<>(batch.size());
        for (float[] v : ordered) {
            if (v == null) {
                throw new IllegalStateException("embedding 返回条数不足，期望 " + batch.size() + " 条");
            }
            out.add(v);
        }
        log.debug("embedding 完成 {} 条，维度 {}", out.size(), out.get(0).length);
        return out;
    }
}