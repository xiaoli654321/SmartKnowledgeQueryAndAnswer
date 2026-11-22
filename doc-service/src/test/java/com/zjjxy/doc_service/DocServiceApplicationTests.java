package com.zjjxy.doc_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文冒烟测试。
 * <p>
 * ★ 必须关掉 Nacos 注册/发现：CI 与本地单测环境都没有注册中心，
 *   而 Nacos 注册失败会 fail-fast 把整个 Spring 上下文拉崩，
 *   结果就是"测试一个都没写，mvn verify 却红"。
 * <p>
 * Kafka / MySQL / Redis 的客户端都是懒连接，不连也不会阻塞上下文启动。
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false"
})
class DocServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
