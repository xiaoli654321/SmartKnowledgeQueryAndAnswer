package com.zjjxy.aiservice.governance;

import com.zjjxy.aiservice.llm.FailoverChatService;
import com.zjjxy.aiservice.llm.LlmReply;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 治理层：限流 + 熔断 + 重试，套在 Failover 外面。
 * <p>
 * 【三个注解的顺序是有讲究的】从上到下由外到内：
 * <pre>
 *   RateLimiter  → 先挡掉超量请求，连尝试的机会都不给（保护下游，也保护自己）
 *   CircuitBreaker → 连续失败到阈值就打开，快速失败，不再把请求打到已经挂掉的下游
 *   Retry        → 对偶发性抖动重试；如果放最外层，重试会绕过熔断计数
 * </pre>
 * <p>
 * ★ 三个 name 必须和 yml 里 resilience4j.*.instances 的 key 一字不差。
 *   【踩过的坑】旧工程注解写 llmRateLimiter、配置写成 ratelimiter，
 *   结果限流器根本没生效，但也不报错 —— 这种"静默失效"比报错可怕得多。
 * <p>
 * ★ fallback 方法签名 = 原方法参数 + 异常类型，且必须同类内可见。
 */
@Service
public class GovernedChatService {

    private final FailoverChatService failoverChatService;

    public GovernedChatService(FailoverChatService failoverChatService) {
        this.failoverChatService = failoverChatService;
    }

    @RateLimiter(name = "llmRateLimiter", fallbackMethod = "rateLimited")
    @CircuitBreaker(name = "llmBreaker", fallbackMethod = "circuitOpen")
    @Retry(name = "llmRetry", fallbackMethod = "retryExhausted")
    public LlmReply chat(String prompt) {
        return failoverChatService.chat(prompt);
    }

    /** 令牌桶满了：返回 429，让调用方知道"等会儿再来"，而不是当成故障 */
    private LlmReply rateLimited(String prompt, RequestNotPermitted e) {
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
    }

    /** 熔断打开：快速失败，不再打下游 */
    private LlmReply circuitOpen(String prompt, CallNotPermittedException e) {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "LLM 服务暂时不可用（熔断中）");
    }

    /** 重试次数用尽：把最后一次的真实错误暴露出去，别吞掉原因 */
    private LlmReply retryExhausted(String prompt, Throwable e) {
        if (e instanceof ResponseStatusException rse) {
            throw rse;      // 已经是明确的业务状态码，原样抛
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "LLM 调用失败：" + e.getMessage());
    }
}