package com.zjjxy.doc_service.service;

import com.zjjxy.doc_service.dto.UploadResult;
import com.zjjxy.doc_service.entity.KbDocument;
import com.zjjxy.doc_service.entity.KbDocumentVersion;

import java.io.IOException;
import java.util.List;

public interface DocService {
//    上传文档的方法
    UploadResult upload(String title , Long tenantId , String filename , byte[] bytes) throws IOException;
//    追加新的文档版本
    UploadResult addVersion(Long docId , Long tenantId , String filename , byte[] bytes) throws IOException;
//      查文档
    KbDocument getDocument(Long docId , Long tenantId);
//    查询版本列表 按照version升序(获得的一定是最新版本)
    List<KbDocumentVersion> getVersion(Long docId , Long tenantId);
    // 列出本租户的文档（后台管理用）
    List<KbDocument> list(Long tenantId , int limit);
//    回滚，把指定版本协会去，并且追加新版本(不删历史)
    KbDocument rollback(Long docId , Long tenantId , int version);
}
