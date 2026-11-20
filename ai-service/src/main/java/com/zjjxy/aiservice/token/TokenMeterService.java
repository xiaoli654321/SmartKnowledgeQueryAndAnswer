package com.zjjxy.aiservice.token;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Token 计量：按租户 / 按供应商累计用量，落 Redis。
 * <p>
 * 为什么必须用 Redis 而不是进程内 Map：
 * ai-service 是无状态的，会横向扩多个实例；用 Map 的话每个实例各算各的，
 * 用户看到的"本月用了多少 token"取决于负载均衡把请求打到哪台 —— 数字毫无意义。
 * <p>
 * key 设计：
 *   token:tenant:{tenantId}   —— 计费/配额用（按租户）
 *   token:provider:{name}     —— 成本分析用（看哪家花得多，决定要不要切换供应商）
 * 生产上还应带时间维度（token:tenant:{id}:2026-11），这里先简化。
 */
@Service
public class TokenMeterService {

    private static final Logger log = LoggerFactory.getLogger(TokenMeterService.class);

    private final StringRedisTemplate redis;

    public TokenMeterService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 记录一次调用的用量 */
    public void record(Long tenantId, String provider, long input, long output) {
        long total = input + output;
        if (total <= 0) {
            log.debug("供应商未返回用量，跳过计量 provider={}", provider);
            return;
        }
        try {
            redis.opsForValue().increment("token:tenant:" + tenantId, total);
            redis.opsForValue().increment("token:provider:" + provider, total);
        } catch (Exception e) {
            // 计量失败不能影响主流程：问答该给的答案还是要给
            log.error("Token 计量写入失败 tenantId={} provider={} err={}", tenantId, provider, e.getMessage());
        }
    }

    public long tenantTotal(Long tenantId) {
        String v = redis.opsForValue().get("token:tenant:" + tenantId);
        return v == null ? 0 : Long.parseLong(v);
    }

    public long providerTotal(String provider) {
        String v = redis.opsForValue().get("token:provider:" + provider);
        return v == null ? 0 : Long.parseLong(v);
    }
}