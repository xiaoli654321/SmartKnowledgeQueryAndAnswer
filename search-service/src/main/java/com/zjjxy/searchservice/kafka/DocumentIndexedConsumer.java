package com.zjjxy.searchservice.kafka;

import com.zjjxy.searchservice.event.DocumentIndexedEvent;
import com.zjjxy.searchservice.service.ChunkIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 消费「文档已索引」事件 → 建索引。
 * <p>
 * 【注解思路】
 * - @KafkaListener(topics = "doc-indexed", groupId = "search-index-group")：
 *   groupId 在 yml 里也配了一份，两处保持一致即可（注解优先）。
 * - 反序列化器与"忽略类型头"都在 yml 的 spring.kafka.consumer 下配好了。
 * <p>
 * 【异常处理策略】这里 catch 住只记日志、不往外抛。
 * 抛出去会触发 Kafka 的默认重试，重试多次仍失败就会跳过该条（或堵住分区，取决于容器配置）。
 * 本项目取"记录错误、继续消费"，避免一条毒消息把整条链路卡死；
 * 生产上更稳妥的做法是失败进死信队列（DLQ），再人工/定时重放。
 */
@Component
public class DocumentIndexedConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentIndexedConsumer.class);

    private final ChunkIndexService indexService;

    public DocumentIndexedConsumer(ChunkIndexService indexService) {
        this.indexService = indexService;
    }

    @KafkaListener(topics = "doc-indexed", groupId = "search-index-group")
    public void onMessage(DocumentIndexedEvent event) {
        log.info("收到索引事件 docId={} version={} chunks={}",
                event.docId(), event.version(),
                event.chunks() == null ? 0 : event.chunks().size());
        try {
            indexService.index(event);
        } catch (Exception e) {
            log.error("建索引失败 docId={} version={}", event.docId(), event.version(), e);
        }
    }
}