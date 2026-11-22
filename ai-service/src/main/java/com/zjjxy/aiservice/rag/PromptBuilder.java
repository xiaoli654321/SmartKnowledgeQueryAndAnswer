package com.zjjxy.aiservice.rag;

import com.zjjxy.aiservice.dto.SearchHitDto;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prompt 组装：把检索到的片段编号后塞进提示词。
 * <p>
 * 三条规则缺一不可：
 * 1) 只用资料回答 → 否则模型拿训练记忆里的"通用常识"答企业制度；
 * 2) 每个结论标 [n] → 这是"可溯源"的落地方式；
 * 3) 资料不足要明说 → 宁可回答"不知道"，也不要编一条看起来很合理的假制度。
 */
@Component
public class PromptBuilder {

    public String build(String question, List<SearchHitDto> hits) {
        StringBuilder sb = new StringBuilder();

        sb.append("【参考资料】\n");
        for (int i = 0; i < hits.size(); i++) {
            SearchHitDto h = hits.get(i);
            sb.append("[").append(i + 1).append("] （文档ID ").append(h.documentId())
              .append("，片段 ").append(h.chunkIndex()).append("）\n")
              .append(h.content()).append("\n\n");
        }

        sb.append("【用户问题】\n").append(question).append("\n\n");

        sb.append("【回答要求】\n")
          .append("1. 只使用上方参考资料中的信息，不要编造，不要引入资料以外的常识；\n")
          .append("2. 答案中的每个关键结论后面用 [编号] 标注依据，例如：入职满一年享 5 天年假 [1][2]；\n")
          .append("3. 如果参考资料不足以回答，直接回答“根据现有知识库无法回答该问题”，不要猜测；\n")
          .append("4. 用简洁的中文回答，不要复述参考资料原文。\n");
        return sb.toString();
    }
}