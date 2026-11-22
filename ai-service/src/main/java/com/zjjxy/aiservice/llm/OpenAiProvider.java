package com.zjjxy.aiservice.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议的供应商（本项目用硅基流动的 GLM-5.3-Flash）。
 *
 * <h3>你要实现的 chat(prompt)</h3>
 * 1) 拼 URL：baseUrl + "/chat/completions"；
 * 2) 请求头：Content-Type: application/json + Authorization: Bearer {apiKey}；
 * 3) 请求体（用 Map 拼即可）：
 *      {"model": model,
 *       "messages": [{"role":"system","content":"你是企业知识库问答助手，只依据给定资料回答。"},
 *                    {"role":"user","content": prompt}],
 *       "temperature": 0.2, "stream": false}
 * 4) RestTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class)；
 * 5) 用 ObjectMapper 解析：choices[0].message.content；
 *    usage.prompt_tokens / usage.completion_tokens。
 * 6) 返回 new LlmReply(content, in, out, name())。
 *
 * <h3>两个必须处理的边界</h3>
 * - choices 缺失（供应商返回错误结构）→ 抛 IllegalStateException，把原始响应前 200 字带出来；
 * - content 为空 → 也抛异常，别把空字符串当成功返回（下游会拿去当答案）。
 */
@Component
public class OpenAiProvider implements LlmProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RestTemplate restTemplate;

    @Value("${llm.provider.openai.base-url}")
    private String baseUrl;
    @Value("${llm.provider.openai.api-key}")
    private String apiKey;
    @Value("${llm.provider.openai.model}")
    private String model;

    public OpenAiProvider(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public String name() {
        return model;
    }

    @Override
    public LlmReply chat(String prompt) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", "你是企业知识库问答助手，只依据给定资料回答。"),
                        Map.of("role", "user", "content", prompt)
                ),
                "temperature", 0.2,
                "stream", false
        );
        String resp = restTemplate.postForObject(baseUrl + "/chat/completions", new HttpEntity<>(body, headers), String.class);
        JsonNode node = MAPPER.readTree(resp);
        // ★ 是 choices（复数）不是 choice：OpenAI 兼容协议里这个字段叫 choices，取第 0 条
        JsonNode choice = node.path("choices").path(0);
        if (choice.isMissingNode()) {
            throw new IllegalStateException("供应商返回结构异常：" + String.valueOf(resp).substring(0, Math.min(200, String.valueOf(resp).length())));
        }
        String content = choice.path("message").path("content").asText();
        JsonNode usage = node.path("usage");
        long in = usage.path("prompt_tokens").asLong(0);
        long out = usage.path("completion_tokens").asLong(0);

        if (content.isBlank()) {
            throw new IllegalStateException("供应商返回空内容");
        }
        return new LlmReply(content, in, out, name());
    }
}