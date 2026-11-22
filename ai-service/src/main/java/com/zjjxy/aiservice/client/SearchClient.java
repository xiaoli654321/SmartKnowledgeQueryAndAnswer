package com.zjjxy.aiservice.client;

import com.zjjxy.aiservice.dto.SearchHitDto;
import com.zjjxy.aiservice.dto.SearchQuery;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 调用 search-service 的 Feign 客户端（声明式，不用你写实现）。
 * <p>
 * name 必须等于 search-service 的 spring.application.name，配合 loadbalancer 走 lb://。
 * 路径与 search-service 的 controller 一致（/search/hybrid）。
 * <p>
 * ★ SearchHitDto 是 search-service 里 SearchHit 的【镜像】：跨服务不共享类，
 *   字段名对不上会得到一堆 null 且不报错。改契约时两边必须同时改。
 */
@FeignClient(name = "search-service")
public interface SearchClient {

    @PostMapping("/search/hybrid")
    List<SearchHitDto> hybrid(@RequestBody SearchQuery query);
}