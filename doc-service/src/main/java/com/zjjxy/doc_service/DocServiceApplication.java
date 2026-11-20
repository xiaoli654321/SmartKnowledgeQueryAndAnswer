package com.zjjxy.doc_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 文档服务启动类。
 * <p>
 * @EnableFeignClients：开启 Feign 客户端扫描。M1/M3 里调 ai-service（Text2SQL、摘要）
 *                      需要它；现在先开着，没有 @FeignClient 也不会报错。
 * <p>
 * 注意包名：本模块包叫 com.zjjxy.doc_service（下划线），是 Spring Initializr 按模块名
 * doc_service 生成的。它不影响运行，只影响"看起来跟别的模块不统一"，
 * 想改的话 IDEA 里 Refactor → Rename Package 一次就行（M1 顺手做）。
 */
@SpringBootApplication
@EnableFeignClients
public class DocServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocServiceApplication.class, args);
    }

}
