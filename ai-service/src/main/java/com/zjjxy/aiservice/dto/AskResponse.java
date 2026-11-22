package com.zjjxy.aiservice.dto;

import java.util.List;

/**
 * 问答响应（契约，给全）。
 * answer    —— 模型基于检索资料生成的答案（带 [n] 引用标注）
 * citations —— 出处列表
 * provider  —— 实际是哪个供应商答的（Failover 时很有用）
 * tokens    —— 真实用量，用于成本核算
 */
public record AskResponse(
        String answer,
        List<Citation> citations,
        String provider,
        long inputTokens,
        long outputTokens,
        long latencyMs
) {
}