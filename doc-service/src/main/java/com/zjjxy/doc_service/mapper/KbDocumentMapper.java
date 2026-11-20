package com.zjjxy.doc_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zjjxy.doc_service.entity.KbDocument;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档表 Mapper。
 * <p>
 * ★ 名字从 KbDocument 改成了 KbDocumentMapper：原来接口名和实体同名、又没 import 实体，
 *   BaseMapper<KbDocument> 里的 KbDocument 指向的是"这个 Mapper 接口自己"，
 *   启动时 MyBatis-Plus 会把它当实体解析，打印：
 *   WARN TableInfoHelper - Can not find table primary key in Class: "...mapper.KbDocument"
 *   改名同时必须 import 实体，泛型才真正指向实体类。
 */
@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocument> {
}
