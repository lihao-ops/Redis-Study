package com.hao.redis.common.interceptor;

import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

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
 * - 使用 ConcurrentHashMap 缓存每个资源的限流器实例。
 * - 动态调整令牌桶速率以适配配置变化。
 */
@Slf4j
@Component
public class SimpleRateLimiter {
    
    // 存储每个接口的限流器
    private final ConcurrentHashMap<String, RateLimiter> limiters = new ConcurrentHashMap<>();
    
    /**
     * 尝试获取令牌
     *
     * 实现逻辑：
     * 1. 获取或创建当前资源的限流器实例。
     * 2. 动态校准速率并尝试获取令牌。
     *
     * @param key 限流键（通常是接口路径）
     * @param qps 每秒允许的请求数
     * @return true表示通过，false表示限流
     */
    public boolean tryAcquire(String key, double qps) {
        // 实现思路：
        // 1. 通过限流键定位或创建限流器。
        // 2. 校准速率后尝试获取令牌。
        // 核心代码：获取或创建限流器
        RateLimiter limiter = limiters.computeIfAbsent(key, k -> {
            log.info("创建限流器|Rate_limiter_created,key={},qps={}", k, qps);
            return RateLimiter.create(qps);
        });
        
        // 动态调整速率（使用Math.abs避免浮点数精度问题）
        if (Math.abs(limiter.getRate() - qps) > 0.0001) {
            limiter.setRate(qps);
        }
        // 立刻拿令牌，不等待
        return limiter.tryAcquire();
    }
}
