package com.zjjxy.aiservice.llm;

/**
 * 大模型供应商抽象。
 * <p>
 * 所有实现都被注入成 List<LlmProvider>，Failover 按 List 的顺序依次尝试。
 * 加一个供应商（通义、文心、本地 vLLM）= 新增一个 @Component 实现，别处不用改。
 */
public interface LlmProvider {

    /** 供应商/模型名，用于日志与 Token 计量 */
    String name();

    /** 发起一次对话；失败直接抛异常，由 Failover 决定是否切换下一家 */
    LlmReply chat(String prompt) throws Exception;
}