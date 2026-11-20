package com.zjjxy.aiservice.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * 本地 Ollama 供应商（兜底）。
 * <p>
 * 用途：云厂商挂了 / 欠费 / 网络不通时，至少还能用本机小模型给出一个（质量差但可用的）回答，
 * 而不是整个问答直接 503。这就是 Failover 的意义 —— 降级，而不是归零。
 * <p>
 * Ollama 的 /api/chat 响应里 usage 字段叫 prompt_eval_count / eval_count，和 OpenAI 不一样，
 * 这类"每家口径不同"的细节正是抽象成 LlmProvider 的价值所在。
 */
@Component
public class OllamaProvider implements LlmProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate;

    @Value("${llm.provider.ollama.base-url}")
    private String baseUrl;
    @Value("${llm.provider.ollama.model}")
    private String model;

    public OllamaProvider(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public String name() {
        return "ollama:" + model;
    }

    @Override
    public LlmReply chat(String prompt) throws Exception {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "stream", false
        );

        String resp = restTemplate.postForObject(baseUrl + "/api/chat", body, String.class);
        JsonNode node = MAPPER.readTree(resp);

        String content = node.path("message").path("content").asText("");
        long in = node.path("prompt_eval_count").asLong(0);
        long out = node.path("eval_count").asLong(0);

        if (content.isBlank()) {
            throw new IllegalStateException("Ollama 返回空内容");
        }
        return new LlmReply(content, in, out, name());
    }
}