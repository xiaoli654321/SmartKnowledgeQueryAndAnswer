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
 * 三条设计原则：
 * 1) @Async：绝不能放在问答热路径上 —— RAGAS 打一次分要好几秒，同步做用户就得多等几秒；
 * 2) 开关控制：metric.service.enabled=false 时直接返回，M3 阶段还没有评测服务，避免刷错误日志；
 * 3) 绝不影响主流程：任何异常都只记 debug，样本丢了就丢了，第二天还有新的。
 * <p>
 * 注意 @Async 生效的前提：启动类要有 @EnableAsync，且调用方是【别的 Bean】
 * （同类内部自调用不走代理，@Async 会退化成同步 —— 这是最常见的 @Async 失效原因）。
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
            body.put("tenantId", hits.isEmpty() ? null : null);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            restTemplate.postForObject(baseUrl + "/metric/sample",
                    new HttpEntity<>(body, headers), String.class);
        } catch (Exception e) {
            log.debug("评测样本上报失败（不影响主流程）：{}", e.getMessage());
        }
    }
}