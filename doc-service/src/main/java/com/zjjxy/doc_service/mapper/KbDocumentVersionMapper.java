package com.zjjxy.doc_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zjjxy.doc_service.entity.KbDocumentVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 版本表 Mapper。
 * <p>
 * selectMaxVersion：BaseMapper 没有"取最大版本号"这个语义，自己写。
 * ★ 必须 IFNULL(...,0)：空表时 MAX(version) 返回 NULL，直接 +1 会 NPE，
 *   而"第一篇文档还没有任何版本"恰恰是最常走到的分支。
 */
@Mapper
public interface KbDocumentVersionMapper extends BaseMapper<KbDocumentVersion> {

    @Select("SELECT IFNULL(MAX(version), 0) FROM kb_document_version WHERE document_id = #{documentId}")
    int selectMaxVersion(@Param("documentId") Long documentId);
}