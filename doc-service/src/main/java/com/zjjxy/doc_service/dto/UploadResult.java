package com.zjjxy.doc_service.dto;

/**
 * 上传/新版本的返回体（数据契约，直接给全）。
 * docId       —— 文档 ID
 * version     —— 这次写入的版本号
 * chunkCount  —— 切出多少块（前端可以展示"已切分 12 段"）
 * charCount   —— 解析出的正文字符数（用来发现"扫描件抽不出字"这类问题）
 */
public record UploadResult(Long docId, int version, int chunkCount, int charCount) {
}
