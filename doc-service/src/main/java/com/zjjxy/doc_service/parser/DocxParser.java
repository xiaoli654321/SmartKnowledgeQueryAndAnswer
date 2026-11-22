package com.zjjxy.doc_service.parser;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Word(.docx) 解析（Apache POI 5.2.5）。
 * <p>
 * ★ 核心点：必须按 getBodyElements() 的顺序遍历，而不是只取 getParagraphs()。
 *   企业制度文档里的"年假天数表""报销标准表"都是表格，只读段落会整张丢掉，
 *   用户问"满一年几天年假"时，答案所在的数字根本不在索引里。
 * <p>
 * 局限：只支持 .docx（OOXML）。老的 .doc 是二进制格式，要 HWPF，兼容性差，这里不支持。
 */
@Component
public class DocxParser implements DocumentParser {

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".docx");
    }

    @Override
//    docx文件本身就是一个二进制压缩文件，所以不能直接用String去接收要使用byte
    public String parse(byte[] bytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    String text = p.getText();
                    if (text != null && !text.isBlank()) {
                        sb.append(text.trim()).append('\n');
                    }
                } else if (element instanceof XWPFTable table) {
                    // 表格按行拼成文本，单元格之间用 | 分隔，保留"天数/标准"这类结构化信息
                    for (XWPFTableRow row : table.getRows()) {
                        StringBuilder line = new StringBuilder();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            if (line.length() > 0) line.append(" | ");
                            line.append(cell.getText().replace('\n', ' ').trim());
                        }
                        if (line.length() > 0) sb.append(line).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }
}