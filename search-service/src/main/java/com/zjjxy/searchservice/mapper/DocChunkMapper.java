package com.zjjxy.searchservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zjjxy.searchservice.dto.KeywordHit;
import com.zjjxy.searchservice.entity.DocChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 分块表 Mapper：一条走全文索引，一条走向量候选。
 * <p>
 * ★ 关键词那一路依赖 doc_chunk 上的 `FULLTEXT KEY ft_content (content) WITH PARSER ngram`。
 *   没有 ngram 分词器的全文索引对中文基本没用（默认分词器把整句当一个词），
 *   所以 init.sql 里建索引时专门带了 WITH PARSER ngram。
 */
@Mapper
public interface DocChunkMapper extends BaseMapper<DocChunk> {

    /**
     * 关键词检索：MySQL 的全文相关度排名。
     * MATCH 出现两次不是笔误：一次拿分数、一次做过滤（WHERE 里不能引用 SELECT 的别名）。
     */
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
     * 向量候选集：把该租户带向量的分块捞出来，在 Java 里算余弦。
     * <p>
     * 为什么不在数据库里算：MySQL 没有向量类型，也没有向量索引，SQL 算余弦要逐行解析 JSON，
     * 反而更慢。数据量小的时候【捞出来在内存算】最直接；数据量大就该换 pgvector / Milvus 了。
     * limit 是保护：别把整库读进内存。
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