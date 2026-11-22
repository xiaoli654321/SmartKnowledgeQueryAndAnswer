package com.zjjxy.doc_service.kafka;

import com.zjjxy.doc_service.event.DocumentIndexedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
public class DocumentEventPublisher {
    private static final String TOPIC = "doc-indexed";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    public DocumentEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(DocumentIndexedEvent event){
        String key =   String.valueOf(event.docId());
        // ★ send() 有两个失败面，必须都接住：
        //   ① 同步抛出：broker 不可用时它会先阻塞 max.block.ms（默认 60s）再抛 KafkaException，
        //      这个异常会从 afterCommit 冒到 Controller，让已经被提交的事务"看起来失败"——
        //      用户等了 60 秒看到 500，其实数据早就落库了；
        //   ② 异步失败：写进 broker 失败，通过 whenComplete 的 exception 返回。
        try {
            CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send(TOPIC, key,event);
            future.whenComplete((r,e)->{
                if(e!=null){
                    log.error("事件发送失败 docId={} version={} err={}", event.docId(), event.version(), e.getMessage());
                    return;
                }
                var meta = r.getRecordMetadata();
                log.info("事件发送成功 topic={} partition={} offset={} docId={} version={} chunk={}", meta.topic(),meta.partition(),meta.offset(),event.docId(),event.version(),event.chunks().size());
            });
        } catch (Exception e) {
            // 事件发不出去不等于文档没存好：记日志，别把错误抛回给上传接口
            log.error("事件发送异常（broker 不可用？）docId={} version={} err={}",
                    event.docId(), event.version(), e.getMessage());
        }
    }
}

