package com.zjjxy.searchservice.event;

import java.util.List;

/**
 * 「文档已索引」事件 —— 这是 doc-service 那边 DocumentIndexedEvent 的镜像。
 * <p>
 * ★ 字段名、类型、顺序必须和生产端一字不差：Kafka 里传的是 JSON，
 *   消费端靠字段名反序列化，差一个字就得到一个 null 字段，而且不会报错（最坑）。
 * 也正因为两端各写一份，yml 里才要：
 *   spring.json.use.type.headers: false                      （忽略生产端写进消息头的类名）
 *   spring.json.value.default.type: com.zjjxy.searchservice.event.DocumentIndexedEvent
 * 否则会去按 doc-service 的类名找类，直接 ClassNotFoundException。
 */
public record DocumentIndexedEvent(
        String eventId,
        Long docId,
        Long tenantId,
        String title,
        int version,
        List<Chunk> chunks,
        String occurredAt
) {
    public record Chunk(int index, String content, int charCount) {
    }
}