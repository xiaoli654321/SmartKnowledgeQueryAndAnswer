package com.zjjxy.doc_service.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档版本（对应 doc_db.kb_document_version）。
 *
 * <pre>
 * 库里实际列（desc kb_document_version）：
 *   id          bigint  PK  auto_increment
 *   document_id bigint  NOT NULL   （普通索引 idx？见下）
 *   version     int     NOT NULL   （(document_id, version) 有唯一索引）
 *   content     longtext NULL
 *   create_time datetime 默认 CURRENT_TIMESTAMP
 * </pre>
 *
 * 追加式版本链：只 INSERT，永不 UPDATE / DELETE 历史行。
 * 回滚 = 把老版本内容写回主表 + 追加一条新版本，而不是"删掉新版本"——
 * 这样任何时刻的"当时是什么内容"都能查得到。
 *
 * 没有 title 字段：标题只在主表 kb_document 里有一份，版本表存正文就够了（避免冗余不一致）。
 */
@Data
@TableName("kb_document_version")
public class KbDocumentVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属文档 ID，对应库里的 document_id */
    private Long documentId;

    /**
     * 版本号，从 1 开始递增。
     * ★ 这个字段必须有：库里是 NOT NULL，漏了就是 Column 'version' cannot be null。
     * 取值逻辑在 Service 里：selectMaxVersion(documentId) + 1。
     */
    private Integer version;

    private String content;

    /** 同主表：INSERT 时自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
