package com.zjjxy.searchservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP 客户端配置：调用远程 embedding 服务用。
 * <p>
 * 超时必须显式设：默认是无限等待，一旦模型侧卡住，检索请求会跟着一起挂住，
 * 表现就是"页面一直在转圈"，非常难查。
 */
@Configuration
public class HttpConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);      // 连不上就早点失败
        factory.setReadTimeout(60_000);        // 批量算向量会慢，给 60s
        return new RestTemplate(factory);
    }
}