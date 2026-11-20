package com.zjjxy.doc_service.service;

import com.zjjxy.doc_service.dto.UploadResult;
import com.zjjxy.doc_service.entity.KbDocument;
import com.zjjxy.doc_service.entity.KbDocumentVersion;

import java.io.IOException;
import java.util.List;

/**
 * 文档服务接口（只有签名，实现你自己写）。
 * <p>
 * 每个方法的 tenantId 参数不是摆设：所有查询/更新都必须带 tenant_id 条件，
 * 否则 A 租户能读到 B 租户的文档 —— 多租户系统最常见的越权漏洞。
 */
public interface DocService {

    /** 新上传一篇文档：解析 -> 切片 -> 存文档 + 版本1 -> 发事件 */
    UploadResult upload(String title, Long tenantId, String filename, byte[] bytes) throws IOException;

    /** 追加新版本：解析 -> 切片 -> 存版本 N -> 更新主表正文 -> 发事件 */
    UploadResult addVersion(Long docId, Long tenantId, String filename, byte[] bytes) throws IOException;

    /** 查文档（必须带 tenantId 过滤） */
    KbDocument get(Long docId, Long tenantId);

    /** 查版本列表（按 version 升序） */
    List<KbDocumentVersion> versions(Long docId, Long tenantId);

    /** 回滚：把指定版本内容写回主表，并追加一条新版本（不删历史） */
    KbDocument rollback(Long docId, Long tenantId, int version);
}
