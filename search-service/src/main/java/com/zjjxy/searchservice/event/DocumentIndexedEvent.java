package com.zjjxy.searchservice.event;

import java.util.List;

/**
 * 「文档已索引」事件 —— doc-service 那边 DocumentIndexedEvent 的镜像。
 * <p>
 * ★ 字段名、类型、顺序必须和生产端一字不差。Kafka 里传的是 JSON，
 *   消费端靠字段名反序列化，差一个字就得到一个 null 字段，而且【不会报错】—— 最难查。
 * <p>
 * 这也解释了 yml 里那三行配置为什么缺一不可：
 *   spring.json.use.type.headers: false   忽略生产端写进消息头的类名
 *   spring.json.value.default.type: ...   指定用本地这个类反序列化
 *   spring.json.trusted.packages: "*"     允许任意包（否则安全校验会拒绝）
 *
 * （这份是数据契约，直接给全，不用你改）
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