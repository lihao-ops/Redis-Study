package com.hao.redis.common.interceptor;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

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
 * - 使用 Guava Cache 缓存限流器实例，替代 ConcurrentHashMap。
 * - 引入过期机制（expireAfterAccess）防止内存泄漏。
 * - 动态调整令牌桶速率以适配配置变化。
 */
@Slf4j
@Component
public class SimpleRateLimiter {
    
    // 存储每个接口的限流器
    // 优化：使用 Guava Cache 替代 Map，设置过期淘汰策略
    private final Cache<String, RateLimiter> limiters = CacheBuilder.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES) // 10分钟无访问自动回收
            .maximumSize(50000) // 限制最大容量，防止恶意刷爆内存
            .build();
    
    /**
     * 尝试获取令牌
     *
     * 实现逻辑：
     * 1. 从 Cache 获取或创建当前资源的限流器实例。
     * 2. 动态校准速率并尝试获取令牌。
     *
     * @param key 限流键（通常是接口路径）
     * @param qps 每秒允许的请求数
     * @return true表示通过，false表示限流
     */
    public boolean tryAcquire(String key, double qps) {
        // 实现思路：
        // 1. 通过限流键从 Cache 中获取限流器。
        // 2. 如果不存在，Cache 会自动调用 Loader 创建。
        // 3. 捕获异常并提供兜底。
        
        RateLimiter limiter;
        try {
            limiter = limiters.get(key, () -> {
                log.info("创建限流器|Rate_limiter_created,key={},qps={}", key, qps);
                return RateLimiter.create(qps);
            });
        } catch (ExecutionException e) {
            log.error("获取限流器异常_使用临时实例兜底|Get_limiter_error_fallback,key={}", key, e);
            // 兜底策略：创建一个临时的限流器，确保业务不中断，但此时限流效果仅对当前请求有效（相当于放行但限制了当前这一瞬间）
            // 或者直接放行。这里选择创建临时实例，尽可能保持限流语义。
            limiter = RateLimiter.create(qps);
        }
        //为了支持“配置中心热更新”场景，让系统在不重启的情况下，实时调整限流阈值。
        // 动态调整速率（使用Math.abs避免浮点数精度问题）
        if (Math.abs(limiter.getRate() - qps) > 0.0001) {
            limiter.setRate(qps);
        }
        // 立刻拿令牌，不等待
        return limiter.tryAcquire();
    }
}
