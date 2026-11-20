package com.zjjxy.doc_service.kafka;

import com.zjjxy.doc_service.event.DocumentIndexedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 事件生产者：把「文档已索引」发给 Kafka。
 * <p>
 * ★ send() 是异步的：返回 CompletableFuture，消息真正写进 broker 才算成功。
 *   如果不接这个回调，Kafka 挂了你的上传接口照样返回 200，但索引永远建不起来 ——
 *   这就是"静默丢消息"，线上最难查的一类问题。
 * <p>
 * key 用 docId：同一篇文档的所有事件进同一个分区，消费端天然按顺序处理，
 * 不会出现"版本 2 先到、版本 1 后到"把索引覆盖回旧内容。
 */
@Component
public class DocumentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DocumentEventPublisher.class);

    /** topic 名：生产端与消费端必须一致 */
    public static final String TOPIC = "doc-indexed";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public DocumentEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(DocumentIndexedEvent event) {
        String key = String.valueOf(event.docId());
        CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send(TOPIC, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("事件发送失败 docId={} version={} err={}", event.docId(), event.version(), ex.getMessage());
                return;
            }
            var meta = result.getRecordMetadata();
            log.info("事件发送成功 topic={} partition={} offset={} docId={} version={} chunks={}",
                    meta.topic(), meta.partition(), meta.offset(),
                    event.docId(), event.version(), event.chunks().size());
        });
    }
}