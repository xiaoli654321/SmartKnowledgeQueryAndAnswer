package com.zjjxy.searchservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zjjxy.searchservice.dto.KeywordHit;
import com.zjjxy.searchservice.entity.DocChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 分块表 Mapper：一条走全文索引，一条捞向量候选。
 * <p>
 * 这两段 SQL 是"基建"，我直接给了；你要理解的是：
 * 1) 关键词那一路为什么必须 WITH PARSER ngram（见 deploy/init.sql 建索引那段）；
 * 2) MATCH 为什么出现两次 —— WHERE 里不能引用 SELECT 的别名，过滤得再写一遍。
 */
@Mapper
public interface DocChunkMapper extends BaseMapper<DocChunk> {

    /** 关键词检索：MySQL 全文相关度排名 */
    @Select("""
            SELECT id, document_id, tenant_id, chunk_index, content,
                   MATCH(content) AGAINST (#{q} IN NATURAL LANGUAGE MODE) AS score
            FROM doc_chunk
            WHERE tenant_id = #{tenantId}
              AND MATCH(content) AGAINST (#{q} IN NATURAL LANGUAGE MODE)
            ORDER BY score DESC
            LIMIT #{limit}
            """)
    List<KeywordHit> keywordSearch(@Param("q") String q,
                                   @Param("tenantId") Long tenantId,
                                   @Param("limit") int limit);

    /**
     * 向量候选集：把该租户带向量的块捞出来，在 Java 里算余弦。
     * <p>
     * 为什么不在 SQL 里算：MySQL 没有向量类型和向量索引，SQL 算余弦要逐行解析 JSON，反而更慢。
     * limit 是保护，别把整库读进内存。
     */
    @Select("""
            SELECT id, document_id, tenant_id, chunk_index, content, embedding
            FROM doc_chunk
            WHERE tenant_id = #{tenantId}
              AND embedding IS NOT NULL
            ORDER BY id
            LIMIT #{limit}
            """)
    List<DocChunk> vectorCandidates(@Param("tenantId") Long tenantId, @Param("limit") int limit);
}