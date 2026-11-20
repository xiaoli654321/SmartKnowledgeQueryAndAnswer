package com.zjjxy.aiservice.client;

import com.zjjxy.aiservice.dto.SearchHitDto;
import com.zjjxy.aiservice.dto.SearchQuery;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 调用 search-service 的 Feign 客户端。
 * <p>
 * name 必须和 search-service 的 spring.application.name 一致，配合 loadbalancer 走 lb://。
 * 路径与 search-service 的 controller 保持一致（/search/hybrid）。
 * <p>
 * 【为什么要单独的 DTO】Feign 跨服务不共享类，检索服务的 SearchHit 在这里要有一份镜像；
 * 字段名对不上就会得到一堆 null，而且不报错。改契约时两边必须同时改。
 */
@FeignClient(name = "search-service")
public interface SearchClient {

    @PostMapping("/search/hybrid")
    List<SearchHitDto> hybrid(@RequestBody SearchQuery query);
}