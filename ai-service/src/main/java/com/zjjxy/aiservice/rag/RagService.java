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
import java.util.List;

/**
 * RAG 编排：检索 → 组装 Prompt → 治理层调模型 → 计量 → 返回答案 + 出处。
 * <p>
 * 两个刻意的设计：
 * 1) 检索为空时【不调模型】，直接返回"没找到"。调了只会得到一段编造的话，既费钱又误导；
 * 2) Token 计量在成功返回之后做，且失败只记日志 —— 计量是旁路，不能拖垮主链路。
 */
@Service
public class RagService {

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
        if (hits == null) hits = List.of();
        log.info("检索完成 question=[{}] hits={}", question, hits.size());

        // 2) 没检索到就别问模型了
        if (hits.isEmpty()) {
            return new AskResponse(
                    "根据现有知识库无法回答该问题（未检索到相关内容）。",
                    List.of(), "none", 0, 0, System.currentTimeMillis() - start);
        }

        // 3) 组装 Prompt 并调用（限流/熔断/重试/Failover 都在 governedChatService 里）
        String prompt = promptBuilder.build(question, hits);
        LlmReply reply = governedChatService.chat(prompt);

        // 4) 计量（旁路，失败不影响答案）
        tokenMeterService.record(tenantId, reply.provider(), reply.inputTokens(), reply.outputTokens());

        // 5) 组装出处
        List<Citation> citations = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            SearchHitDto h = hits.get(i);
            String snippet = h.content() == null ? "" : h.content().substring(0, Math.min(160, h.content().length()));
            citations.add(new Citation(i + 1, h.documentId(), h.chunkIndex(), snippet, h.score()));
        }

        long latency = System.currentTimeMillis() - start;

        // 6) 异步送评测样本（不阻塞用户；metric-service 没起时会自动跳过）
        metricReporter.reportAsync(question, hits, reply.content());

        return new AskResponse(reply.content(), citations, reply.provider(),
                reply.inputTokens(), reply.outputTokens(), latency);
    }
}