package com.zjjxy.searchservice.controller;

import com.zjjxy.searchservice.dto.SearchHit;
import com.zjjxy.searchservice.dto.SearchRequest;
import com.zjjxy.searchservice.service.HybridSearchService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 检索接口。
 * <p>
 * 用 POST + JSON body 而不是 GET 参数：query 可能是一整段话，塞 URL 会撞长度上限。
 * 路径前缀 /search 必须和网关的路由断言 Path=/search/** 对上。
 */
@RestController
@RequestMapping("/search")
public class SearchController {

    private final HybridSearchService hybridSearchService;

    public SearchController(HybridSearchService hybridSearchService) {
        this.hybridSearchService = hybridSearchService;
    }

    /**
     * 混合检索。
     * 请求：{"query":"入职满一年能休几天年假","tenantId":1001,"topK":3}
     * 返回：按 RRF 分降序的分块，带 keywordRank / vectorRank 便于排查是哪一路召回
     */
    @PostMapping("/hybrid")
    public List<SearchHit> hybrid(@RequestBody SearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (request.tenantId() == null) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        return hybridSearchService.hybrid(request);
    }
}