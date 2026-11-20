package com.zjjxy.doc_service.parser;

import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/**
 * 纯文本解析：.txt / .md
 * <p>
 * 为什么要有 GBK 回退：中文 Windows 上很多老 txt 是 GBK 编码，直接按 UTF-8 解会变成
 * 一堆"锟斤拷"；而 UTF-8 解 GBK 文本一定会出现替换字符 \uFFFD，用它当探针就能判断。
 */
@Component
public class TxtParser implements DocumentParser {

    @Override
    public boolean supports(String filename) {
        if (filename == null) return false;
        String n = filename.toLowerCase();
        return n.endsWith(".txt") || n.endsWith(".md");
    }

    @Override
    public String parse(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') < 0) {
            return utf8;                       // 干净，就是 UTF-8
        }
        try {
            return new String(bytes, "GBK");   // 有乱码，按 GBK 再解一次
        } catch (UnsupportedEncodingException e) {
            return utf8;                       // 理论上不会发生，兜底
        }
    }
}