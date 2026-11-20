package com.zjjxy.aiservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * AI 服务启动类。
 * <p>
 * @EnableFeignClients：开启 Feign 客户端扫描。M3 里调 search-service、调 metric-service 都要靠它；
 *                      现在先开着，没有 @FeignClient 也不会报错。
 * @EnableAsync      ：M3 的"异步送评测样本"用 @Async，需要这个开关；
 *                      不开的话 @Async 方法会退化成同步执行，把问答接口拖慢。
 */
@SpringBootApplication
@EnableFeignClients
@EnableAsync
public class AiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }

}
