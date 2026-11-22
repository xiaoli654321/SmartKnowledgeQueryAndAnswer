package com.zjjxy.aiservice.governance;

import com.zjjxy.aiservice.llm.FailoverChatService;
import com.zjjxy.aiservice.llm.LlmReply;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 治理层：限流 + 熔断 + 重试，套在 Failover 外面。
 * <p>
 * 注解顺序（从上到下由外到内）：先挡超量 → 再快速失败 → 最后重试。
 * 三个 name 必须与 yml 的 resilience4j.*.instances 一字不差，否则静默失效。
 */
@Service
public class GovernedChatService {

    private static final Logger log = LoggerFactory.getLogger(GovernedChatService.class);

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

    /** 令牌桶满：429，"等会儿再来"，不是故障 */
    private LlmReply rateLimited(String prompt, RequestNotPermitted e) {
        log.warn("触发限流：{}", e.getMessage());
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
    }

    /** 熔断打开：503，快速失败，不再打下游 */
    private LlmReply circuitOpen(String prompt, CallNotPermittedException e) {
        log.warn("触发熔断，快速失败");
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "LLM 服务暂时不可用（熔断中）");
    }

    /** 重试耗尽：把真实错误透出去，别吞原因 */
    private LlmReply retryExhausted(String prompt, Throwable e) {
        if (e instanceof ResponseStatusException rse) {
            throw rse;   // 已经是明确的状态码（429/503），原样抛
        }
        log.error("重试耗尽：{}", e.getMessage());
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "LLM 调用失败：" + e.getMessage());
    }
}