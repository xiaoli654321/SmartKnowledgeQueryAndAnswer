package com.zjjxy.doc_service.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * PDF 解析（Apache PDFBox 3.0.2）。
 * <p>
 * ★ 3.x 的入口是 Loader.loadPDF(byte[])，2.x 的 PDDocument.load(...) 已经删掉了。
 * ★ try-with-resources 必须写：PDDocument 不关会一直占着内存，批量上传几百份就 OOM。
 * ★ setSortByPosition(true)：按页面坐标顺序抽字，避免双栏排版的文字左右串行。
 * <p>
 * 局限：只能抽文本层。扫描件（图片型 PDF）抽出来是空串，那种要 OCR，本项目不做。
 */
@Component
public class PdfParser implements DocumentParser {

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".pdf");
    }

    @Override
    public String parse(byte[] bytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        } catch (IOException e) {
            // 加密 PDF / 损坏文件都会走到这里，包一层让上层能给出可读的错误
            throw new IOException("PDF 解析失败：" + e.getMessage(), e);
        }
    }
}