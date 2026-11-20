package com.zjjxy.doc_service.parser;

import java.io.IOException;

/**
 * 文档解析器：把"文件字节"变成"纯文本"。
 * <p>
 * 【注解思路】接口不加注解，由实现类各自 @Component 交给 Spring 管理。
 * <p>
 * 【为什么抽接口】解析是唯一跟文件格式耦合的地方。以后加 PPT/Excel/扫描件 OCR，
 * 只是多一个实现 + 工厂里多一行，上层 Service 一行都不用改。
 */
public interface DocumentParser {

    /** 是否支持这个文件名（按扩展名判断，忽略大小写） */
    boolean supports(String filename);

    /** 解析成纯文本；解析失败抛 IOException，由上层转成 400 给用户看 */
    String parse(byte[] bytes) throws IOException;
}
