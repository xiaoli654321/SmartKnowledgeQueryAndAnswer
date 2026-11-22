package com.zjjxy.doc_service.event;

/*
* record 这个是数据契约，两端的名字必须一字不差否则反序列给的就是null
* */

import java.util.List;

public record DocumentIndexedEvent(String eventId , Long docId , Long tenantId , String title , int version , List<Chunk> chunks , String occurredAt) {
    public  record Chunk(int index , String content , int charCount) {}
}
