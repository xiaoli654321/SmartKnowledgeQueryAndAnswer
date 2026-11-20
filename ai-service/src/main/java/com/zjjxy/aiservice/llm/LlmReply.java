package com.zjjxy.aiservice.llm;

/**
 * 一次模型调用的结果。
 * <p>
 * ★ 为什么要把 token 用量一起带回来：Token 计量必须用【供应商真实返回的用量】，
 *   自己在 Java 里按字数估算永远是错的（中英文、标点、特殊符号的计费口径都不一样），
 *   而"计量不准"直接导致成本核算和限流失真。
 */
public record LlmReply(
        String content,
        long inputTokens,
        long outputTokens,
        String provider
) {
    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}