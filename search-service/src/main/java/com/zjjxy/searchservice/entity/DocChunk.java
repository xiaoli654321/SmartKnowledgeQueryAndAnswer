package com.zjjxy.searchservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档分块（对应 search_db.doc_chunk）。
 *
 * <pre>
 * 库里实际列：
 *   id / document_id / tenant_id / chunk_index / content
 *   embedding        LONGTEXT  向量：1024 维 float 的 JSON 数组
 *   embedding_model  VARCHAR   记录是哪代模型算的，换模型时据此重算
 *   create_time      DATETIME
 * </pre>
 *
 * （实体是数据映射，直接给全，不用你改）
 */
@Data
@TableName("doc_chunk")
public class DocChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;
    private Long tenantId;
    private Integer chunkIndex;
    private String content;

    /** 向量（JSON 数组文本）。为空表示这轮 embedding 失败，只有关键词路可用 */
    private String embedding;
    private String embeddingModel;

    private LocalDateTime createTime;
}