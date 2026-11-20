package com.zjjxy.doc_service.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档（对应 doc_db.kb_document）。
 *
 * <pre>
 * 库里实际列（desc kb_document）：
 *   id          bigint       PK  auto_increment
 *   tenant_id   bigint       NOT NULL
 *   title       varchar(255) NOT NULL
 *   content     longtext     NULL
 *   create_time datetime     默认 CURRENT_TIMESTAMP
 * </pre>
 *
 * 字段和列一一对应，一个都不能多 —— 多一个 MyBatis-Plus 就会把它拼进 INSERT 的列名里，
 * 报 Unknown column 'xxx'；少一个 NOT NULL 的，就会报 Column 'xxx' cannot be null。
 *
 * 类型对得上：库是 bigint → Java 用 Long；库是 int → Java 用 Integer。
 * 用 Integer 存 bigint 的 id，数据量小时看不出问题，量一大就溢出。
 */
@Data
@TableName("kb_document")
public class KbDocument {

    /** 自增主键；AUTO 表示用数据库自增，插入后 MP 会把生成的 id 回填到这个字段上 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户 ID。
     * 注意写法是 tenantId（驼峰），不是 tenant_Id —— 库里的 tenant_id 由
     * mybatis-plus 的 map-underscore-to-camel-case 自动转换，不要自己带下划线。
     */
    private Long tenantId;

    private String title;

    /** 当前版本全文；历史版本在 kb_document_version 里 */
    private String content;

    /**
     * 创建时间：INSERT 时由 MyMetaObjectHandler 自动填充（FieldFill.INSERT）。
     * 库里这一列也有 DEFAULT CURRENT_TIMESTAMP 兜底，两不冲突 —— 但有填充时以 Java 侧时间为准。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
