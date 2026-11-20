package com.zjjxy.doc_service.event;

import java.util.List;

/**
 * 「文档已索引」事件：doc-service 生产，search-service 消费。
 * <p>
 * 这是一个"数据契约"，两端的字段名必须一字不差，否则反序列化出来是 null。
 * 所以这个 record 我直接给全（不含逻辑），你要看清楚每个字段是给谁用的：
 * <p>
 * eventId   —— 事件唯一 ID，消费端做幂等/去重
 * docId     —— 文档 ID（同时用作 Kafka 消息 key，保证同一文档进同一分区、有序）
 * tenantId  —— 租户：检索时必须按租户过滤，绝不能串数据
 * title     —— 标题，回答里要展示出处
 * version   —— 版本号：同一文档重复索引时以版本更大的为准
 * chunks    —— 分好片的正文（事件里直接带，search-service 就不用回查 doc_db）
 * occurredAt—— ISO-8601 字符串（不用 Instant，避免两端 Jackson 时间配置不一致）
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
    /** 一个分块：index 是块序号（用于回溯原文位置），content 就是喂给检索的文本 */
    public record Chunk(int index, String content, int charCount) {
    }
}
