package com.zjjxy.aiservice.controller;

import com.zjjxy.aiservice.dto.AskRequest;
import com.zjjxy.aiservice.dto.AskResponse;
import com.zjjxy.aiservice.rag.RagService;
import com.zjjxy.aiservice.token.TokenMeterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 服务对外接口。路径前缀 /ai 必须和网关路由 Path=/ai/** 对上。
 */
@RestController
@RequestMapping("/ai")
public class AiController {

    private final RagService ragService;
    private final TokenMeterService tokenMeterService;

    public AiController(RagService ragService, TokenMeterService tokenMeterService) {
        this.ragService = ragService;
        this.tokenMeterService = tokenMeterService;
    }

    /**
     * RAG 问答。
     * 请求：{"question":"入职满一年能休几天年假","tenantId":1001}
     * 返回：答案 + 出处 + 实际供应商 + token 用量 + 耗时
     */
    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        if (request.tenantId() == null) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        return ragService.ask(request);
    }

    /** 查某租户累计 token */
    @GetMapping("/token/tenant/{tenantId}")
    public long tenantTokens(@PathVariable("tenantId") Long tenantId) {
        return tokenMeterService.tenantTotal(tenantId);
    }

    /** 查某供应商累计 token */
    @GetMapping("/token/provider/{provider}")
    public long providerTokens(@PathVariable("provider") String provider) {
        return tokenMeterService.providerTotal(provider);
    }
}