package com.zjjxy.searchservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP 客户端（调 embedding 服务用）。
 * <p>
 * 超时必须显式设：默认是无限等待，模型侧一卡，检索请求就跟着一起挂住，
 * 现象是"页面一直转圈"，非常难查。
 * <p>
 * （固定写法，不用你改）
 */
@Configuration
public class HttpConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);      // 连不上早点失败
        factory.setReadTimeout(60_000);        // 批量算向量会慢
        return new RestTemplate(factory);
    }
}