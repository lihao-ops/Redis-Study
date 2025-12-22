package com.hao.redis.common.interceptor;

import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 单机限流器
 *
 * 类职责：
 * 为每个资源键维护本地令牌桶，实现快速限流。
 *
 * 设计目的：
 * 1. 在入口处进行低成本限流，保护当前节点。
 * 2. 与分布式限流形成多级防护。
 *
 * 为什么需要该类：
 * 单机限流是第一道防线，可在 Redis 不可用时提供兜底保护。
 *
 * 核心实现思路：
 * - 核心优化：使用 Caffeine 替代 Guava Cache，以获得更优的并发性能和抗扫描攻击能力。
 * - 依赖注入：从 CacheConfig 中注入统一配置的 Cache 实例。
 * - 动态调整令牌桶速率以适配配置变化。
 */
@Slf4j
@Component
public class SimpleRateLimiter {
    
    // 核心优化：注入由 CacheConfig 统一管理的 Caffeine 实例
    private final Cache<String, RateLimiter> limiters;

    @Autowired
    public SimpleRateLimiter(@Qualifier("rateLimiterCache") Cache<String, RateLimiter> rateLimiterCache) {
        this.limiters = rateLimiterCache;
    }
    
    /**
     * 尝试获取令牌
     *
     * 实现逻辑：
     * 1. 从 Caffeine Cache 获取或创建当前资源的限流器实例。
     * 2. 动态校准速率并尝试获取令牌。
     *
     * @param key 限流键（通常是接口路径或用户ID）
     * @param qps 每秒允许的请求数
     * @return true表示通过，false表示限流
     */
    public boolean tryAcquire(String key, double qps) {
        // 实现思路：
        // 1. Caffeine 的 get 方法是线程安全的，如果 Key 不存在，Lambda 表达式会被执行并存入缓存。
        // 2. 捕获异常并提供兜底。
        
        RateLimiter limiter;
        try {
            // 核心代码：使用 Caffeine 的 get 方法实现“获取或创建”
            limiter = limiters.get(key, k -> {
                log.info("创建限流器|Rate_limiter_created,key={},qps={}", k, qps);
                return RateLimiter.create(qps);
            });
        } catch (Exception e) {
            // 注意：Caffeine 的 get 方法会把 Lambda 中抛出的异常包装在 RuntimeException 或 UncheckedExecutionException 中
            log.error("获取限流器异常_使用临时实例兜底|Get_limiter_error_fallback,key={}", key, e);
            // 兜底策略：创建一个临时的限流器，确保业务不中断
            limiter = RateLimiter.create(qps);
        }
        
        // 动态调整速率（使用Math.abs避免浮点数精度问题）
        if (Math.abs(limiter.getRate() - qps) > 0.0001) {
            limiter.setRate(qps);
        }

        // 立刻拿令牌，不等待
        return limiter.tryAcquire();
    }
}
