package com.zjjxy.aiservice.rag;

import com.zjjxy.aiservice.client.SearchClient;
import com.zjjxy.aiservice.dto.AskRequest;
import com.zjjxy.aiservice.dto.AskResponse;
import com.zjjxy.aiservice.dto.Citation;
import com.zjjxy.aiservice.dto.SearchHitDto;
import com.zjjxy.aiservice.dto.SearchQuery;
import com.zjjxy.aiservice.eval.MetricReporter;
import com.zjjxy.aiservice.governance.GovernedChatService;
import com.zjjxy.aiservice.llm.LlmReply;
import com.zjjxy.aiservice.token.TokenMeterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 编排：检索 → 组装 Prompt → 治理层调模型 → 计量 → 返回答案 + 出处。
 * <p>
 * 两个刻意的设计：
 * 1) 检索为空时【不调模型】，直接返回"没找到"。调了只会得到一段编造的话，既费钱又误导；
 * 2) 计量和评测都是旁路：任何异常只记日志，不能影响答案返回。
 */
@Service
public class RagService {

    /** 匹配答案里的 [1] [2] 这类出处标记 */
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)\\]");


    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final SearchClient searchClient;
    private final PromptBuilder promptBuilder;
    private final GovernedChatService governedChatService;
    private final TokenMeterService tokenMeterService;
    private final MetricReporter metricReporter;

    @Value("${rag.top-k:5}")
    private int topK;

    public RagService(SearchClient searchClient,
                      PromptBuilder promptBuilder,
                      GovernedChatService governedChatService,
                      TokenMeterService tokenMeterService,
                      MetricReporter metricReporter) {
        this.searchClient = searchClient;
        this.promptBuilder = promptBuilder;
        this.governedChatService = governedChatService;
        this.tokenMeterService = tokenMeterService;
        this.metricReporter = metricReporter;
    }

    public AskResponse ask(AskRequest request) {
        long start = System.currentTimeMillis();
        Long tenantId = request.tenantId();
        String question = request.question();

        // 1) 检索
        List<SearchHitDto> hits = searchClient.hybrid(new SearchQuery(question, tenantId, topK));
        if (hits == null) {
            hits = List.of();
        }
        log.info("检索完成 question=[{}] hits={}", question, hits.size());

        // 2) 没检索到就别问模型了
        if (hits.isEmpty()) {
            return new AskResponse("根据现有知识库无法回答该问题（未检索到相关内容）。",
                    List.of(), "none", 0, 0, System.currentTimeMillis() - start);
        }

        // 3) 组装 Prompt 并调用（限流/熔断/重试/Failover 都在 governedChatService 里）
        String prompt = promptBuilder.build(question, hits);
        LlmReply reply = governedChatService.chat(prompt);

        // 4) 计量（旁路，失败不影响答案）
        tokenMeterService.record(tenantId, reply.provider(), reply.inputTokens(), reply.outputTokens());

        // 5) 组装出处
        //    ★ 只保留答案真正引用过的 [n]：
        //    检索永远会返回 topK 条（凑数），但答案里只用了其中一两条。
        //    如果原样返回 5 条，用户会看到 4 条毫不相关的"出处"——
        //    这不是信息更多，是误导，而且会让人以为系统检索很准。
        //    注意：保留原始编号（不重新编号），否则和答案里的 [n] 对不上。
        Set<Integer> cited = new LinkedHashSet<>();
        Matcher m = CITATION_PATTERN.matcher(reply.content());
        while (m.find()) {
            cited.add(Integer.parseInt(m.group(1)));
        }

        List<Citation> citations = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            int index = i + 1;
            if (!cited.isEmpty() && !cited.contains(index)) {
                continue;
            }
            SearchHitDto h = hits.get(i);
            String snippet = h.content() == null ? ""
                    : h.content().substring(0, Math.min(160, h.content().length()));
            citations.add(new Citation(index, h.documentId(), h.chunkIndex(), snippet, h.score()));
        }

        // 模型偶尔忘了标出处：保留前两条兜底，避免"答案无出处"这种更差的结果
        if (citations.isEmpty() && !hits.isEmpty()) {
            log.warn("答案未标注任何出处，回退为前 2 条 question={}", question);
            for (int i = 0; i < Math.min(2, hits.size()); i++) {
                SearchHitDto h = hits.get(i);
                String snippet = h.content() == null ? ""
                        : h.content().substring(0, Math.min(160, h.content().length()));
                citations.add(new Citation(i + 1, h.documentId(), h.chunkIndex(), snippet, h.score()));
            }
        }

        long latency = System.currentTimeMillis() - start;

        // 6) 异步送评测样本（不阻塞用户）
        metricReporter.reportAsync(question, hits, reply.content());

        return new AskResponse(reply.content(), citations, reply.provider(),
                reply.inputTokens(), reply.outputTokens(), latency);
    }
}