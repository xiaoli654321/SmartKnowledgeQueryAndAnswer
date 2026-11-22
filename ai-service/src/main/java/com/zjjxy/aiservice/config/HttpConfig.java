package com.zjjxy.aiservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate：调 LLM 用。
 * <p>
 * ★ 读超时必须给足：大模型生成长答案动辄几十秒，超时短了会频繁"假失败"，
 * 而假失败会触发 Failover 切到备用供应商 —— 明明主供应商没问题，流量却打到了备份上。
 * <p>
 * （固定写法，不用你改）
 */
@Configuration
public class HttpConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(120_000);
        return new RestTemplate(factory);
    }
}