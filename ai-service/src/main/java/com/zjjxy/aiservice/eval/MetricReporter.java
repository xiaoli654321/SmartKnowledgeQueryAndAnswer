package com.zjjxy.aiservice.eval;

import com.zjjxy.aiservice.dto.SearchHitDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 异步送评测样本给 metric-service（M4）。
 * <p>
 * 三条原则：@Async 不阻塞热路径；开关控制（服务没起时不刷错误日志）；异常绝不影响主流程。
 * <p>
 * ★ @Async 生效的两个前提：启动类有 @EnableAsync；调用方是【别的 Bean】
 *   （同类内部自调用不走代理，@Async 会静默退化成同步）。
 */
@Service
public class MetricReporter {

    private static final Logger log = LoggerFactory.getLogger(MetricReporter.class);

    private final RestTemplate restTemplate;

    @Value("${metric.service.base-url}")
    private String baseUrl;
    @Value("${metric.service.enabled:false}")
    private boolean enabled;

    public MetricReporter(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Async
    public void reportAsync(String question, List<SearchHitDto> hits, String answer) {
        if (!enabled) {
            return;
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("question", question);
            body.put("contexts", hits.stream().map(SearchHitDto::content).toList());
            body.put("answer", answer);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForObject(baseUrl + "/metric/sample", new HttpEntity<>(body, headers), String.class);
        } catch (Exception e) {
            log.debug("评测样本上报失败（不影响主流程）：{}", e.getMessage());
        }
    }
}