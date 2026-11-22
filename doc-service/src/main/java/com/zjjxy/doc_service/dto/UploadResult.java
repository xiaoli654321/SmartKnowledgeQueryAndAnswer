package com.zjjxy.doc_service.dto;

//上传文档的数据契约
public record UploadResult(Long docId , int version , int chunkCount , int charCount){
}
