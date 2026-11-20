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
 * 多供应商 Failover：按注入顺序逐个尝试，成功即返回。
 * <p>
 * 顺序决定降级顺序，这里在构造器里显式排序（云厂商在前、本地 ollama 在后），
 * 不依赖 Spring 收集 @Component 的"碰巧顺序"。
 * <p>
 * ★ 自定义指标（讲义作业）：给每个供应商单独记耗时与成功/失败次数。
 *   为什么要按 provider 打标签：只有一个笼统的"LLM 总耗时"时，
 *   你根本分不清"是云端变慢了"还是"降级到本地小模型了"——
 *   这两件事的处置方式完全不同（前者找供应商，后者查云账号/额度）。
 */
@Service
public class FailoverChatService {

    private static final Logger log = LoggerFactory.getLogger(FailoverChatService.class);

    private final List<LlmProvider> providers;

    /** Micrometer 注册表：指标最终会通过 /actuator/prometheus 暴露给 Prometheus */
    private final MeterRegistry meterRegistry;

    public FailoverChatService(List<LlmProvider> providers, MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        // 显式排序：云厂商（openai 协议）在前，本地 ollama 兜底在后。
        // ★ 这里踩过一次坑：写成 Boolean.compare(bContains, aContains) 会把顺序弄反，
        //   结果每次都先打本地小模型 —— 又慢又差，而且日志里看不出错（因为"成功"了）。
        this.providers = providers.stream()
                .sorted(java.util.Comparator.comparing(p -> p.name().contains("ollama")))
                .toList();
    }

    public LlmReply chat(String prompt) {
        Exception last = null;
        for (LlmProvider p : providers) {
            // 计时围绕单次供应商调用，标签带上 provider 与结果
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
                // 这一行日志非常关键：排障时能立刻看出"是不是供应商侧的问题"
                log.warn("供应商 {} 失败，切换下一家：{}", p.name(), e.getMessage());
            }
        }
        log.error("所有 LLM 供应商均失败");
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "所有 LLM 供应商均不可用" + (last != null ? "：" + last.getMessage() : ""));
    }
}
