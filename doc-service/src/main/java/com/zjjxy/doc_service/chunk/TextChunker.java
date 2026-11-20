package com.zjjxy.doc_service.chunk;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文本切片器：把一篇长文切成固定大小的块，块与块之间留重叠。
 * <p>
 * 三个原则：
 * 1) 尽量在句末标点处切（。！？；换行），别把一句话劈断，否则块的语义是残的；
 * 2) 单块 500 字左右：太小会把一条制度拆散、太大会稀释语义导致检索变模糊；
 * 3) 相邻块重叠 100 字：防止答案正好落在切口上，两块各缺一半。
 * <p>
 * 这是最朴素的"固定窗口切法"。生产上还有语义切分（按标题层级聚合），
 * 但对制度类文档，按句 + 定长窗口已经够用，而且可解释、可复现、好调试。
 */
@Component
public class TextChunker {

    /** 单块最大字符数（中文按字符算） */
    private static final int MAX_CHARS = 500;
    /** 相邻块重叠字符数 */
    private static final int OVERLAP_CHARS = 100;

    /** 句末标点后切开；零宽断言，标点本身保留在前一句末尾 */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？；!?;\\n])");
    /** 连续空格/全角空格压成一个 */
    private static final Pattern SPACES = Pattern.compile("[ \\t\\u3000]+");
    /** 3 个以上换行压成 2 个 */
    private static final Pattern MANY_NEWLINES = Pattern.compile("\\n{3,}");

    /**
     * @param raw 解析出来的全文
     * @return 切片列表；空文本返回空列表（上层据此报"解析结果为空"）
     */
    public List<String> split(String raw) {
        if (raw == null) return List.of();
        String text = normalize(raw);
        if (text.isBlank()) return List.of();

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String sentence : SENTENCE_END.split(text)) {
            if (sentence.isBlank()) continue;

            // 极端情况：一整段没有任何标点（比如超长编号串），只能按长度硬切，否则这一块永远超长
            for (String piece : hardSplit(sentence)) {
                if (current.length() > 0 && current.length() + piece.length() > MAX_CHARS) {
                    // 先 trim 成最终落库的块，再从"这一块"取尾部重叠，
                    // 保证下一块的开头严格等于上一块结尾的 OVERLAP_CHARS 个字（不会被空白吃掉一位）
                    String finished = current.toString().trim();
                    chunks.add(finished);
                    String overlap = tailOf(finished, OVERLAP_CHARS);
                    current.setLength(0);
                    current.append(overlap);
                }
                current.append(piece);
            }
        }
        if (!current.toString().isBlank()) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    /** 统一换行、去掉 BOM、压缩空白 —— 让同一份文档无论排版差异都切出一样的结果 */
    private String normalize(String raw) {
        String s = raw.replace("\r\n", "\n").replace('\r', '\n').replace("\uFEFF", "");
        s = SPACES.matcher(s).replaceAll(" ");
        s = MANY_NEWLINES.matcher(s).replaceAll("\n\n");
        return s.trim();
    }

    /** 超长且无标点的句子按 MAX_CHARS 硬切，保证不产出超长块 */
    private List<String> hardSplit(String sentence) {
        if (sentence.length() <= MAX_CHARS) return List.of(sentence);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < sentence.length(); i += MAX_CHARS) {
            out.add(sentence.substring(i, Math.min(sentence.length(), i + MAX_CHARS)));
        }
        return out;
    }

    /** 取尾部 n 个字符做重叠区 */
    private String tailOf(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}