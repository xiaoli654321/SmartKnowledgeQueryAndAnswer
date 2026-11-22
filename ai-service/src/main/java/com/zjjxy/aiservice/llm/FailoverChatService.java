package com.zjjxy.aiservice.llm;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 多供应商 Failover：按顺序逐个尝试，成功即返回；全失败才抛 503。
 * <p>
 * ★ 排序决定降级顺序：非 ollama（云厂商）在前，本地小模型兜底在后。
 *   这里踩过坑：写成 Boolean.compare(bContains, aContains) 会把顺序弄反，
 *   结果每次都先打本地小模型 —— 又慢又差，日志里还显示"成功"。
 * <p>
 * ★ 每个供应商单独计时（provider 标签）：只有一个笼统的"LLM 耗时"时，
 *   你分不清"云端变慢了"还是"降级到本地了"，这两件事的处置方式完全不同。
 * <p>
 * Failover 只负责"换一家试试"；限流/熔断/重试是外层 GovernedChatService 的事。
 */
@Service
public class FailoverChatService {

    private static final Logger log = LoggerFactory.getLogger(FailoverChatService.class);

    private final List<LlmProvider> providers;
    private final MeterRegistry meterRegistry;

    public FailoverChatService(List<LlmProvider> providers, MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.providers = providers.stream()
                .sorted(java.util.Comparator.comparing(p -> p.name().contains("ollama")))
                .toList();
        log.info("LLM 供应商顺序：{}", this.providers.stream().map(LlmProvider::name).toList());
    }

    public LlmReply chat(String prompt) {
        Exception last = null;
        for (LlmProvider p : providers) {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                LlmReply reply = p.chat(prompt);
                sample.stop(Timer.builder("llm.call.duration")
                        .description("LLM 单次调用耗时")
                        .tag("provider", p.name())
                        .tag("result", "success")
                        .register(meterRegistry));
                meterRegistry.counter("llm.call.total", "provider", p.name(), "result", "success").increment();
                log.info("LLM 调用成功 provider={} tokens={}+{}", reply.provider(), reply.inputTokens(), reply.outputTokens());
                return reply;
            } catch (Exception e) {
                sample.stop(Timer.builder("llm.call.duration")
                        .description("LLM 单次调用耗时")
                        .tag("provider", p.name())
                        .tag("result", "fail")
                        .register(meterRegistry));
                meterRegistry.counter("llm.call.total", "provider", p.name(), "result", "fail").increment();
                last = e;
                // ★ 这行日志是排障命脉：一眼看出是不是供应商侧问题
                log.warn("供应商 {} 失败，切换下一家：{}", p.name(), e.getMessage());
            }
        }
        log.error("所有 LLM 供应商均失败");
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "所有 LLM 供应商均不可用" + (last != null ? "：" + last.getMessage() : ""));
    }
}