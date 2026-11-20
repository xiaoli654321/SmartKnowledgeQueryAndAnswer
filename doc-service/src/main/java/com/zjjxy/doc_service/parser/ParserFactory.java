package com.zjjxy.doc_service.parser;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * 解析器工厂：按文件名挑一个能处理的解析器。
 * <p>
 * 构造器注入 List<DocumentParser>：Spring 会把所有实现类都塞进来（顺序无所谓，
 * 因为靠扩展名匹配）。以后加 PPT/Excel/OCR，只要新增一个 @Component 实现，
 * 这个类一行都不用改 —— 这就是"对扩展开放、对修改关闭"。
 */
@Component
public class ParserFactory {

    private final List<DocumentParser> parsers;

    public ParserFactory(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    /**
     * @throws IllegalArgumentException 类型不支持（上层转 400）
     * @throws IOException              文件损坏 / 加密（上层转 400 并给出原因）
     */
    public String parse(String filename, byte[] bytes) throws IOException {
        DocumentParser parser = parsers.stream()
                .filter(p -> p.supports(filename))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "不支持的文件类型：" + filename + "，目前支持 .txt / .md / .pdf / .docx"));
        return parser.parse(bytes);
    }
}