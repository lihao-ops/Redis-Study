package com.hao.redis.common.interceptor;

import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 单实例限流器
 * 只用 Guava RateLimiter，无需 Redis
 */
@Slf4j
@Component
public class SimpleRateLimiter {
    
    // 存储每个接口的限流器
    private final ConcurrentHashMap<String, RateLimiter> limiters = new ConcurrentHashMap<>();
    
    /**
     * 尝试获取令牌
     * @param key 限流键（通常是接口路径）
     * @param qps 每秒允许的请求数
     * @return true=通过，false=限流
     */
    public boolean tryAcquire(String key, double qps) {
        RateLimiter limiter = limiters.computeIfAbsent(key, k -> {
            log.info("创建限流器: key={}, qps={}", k, qps);
            return RateLimiter.create(qps);
        });
        
        // 动态调整速率 (使用 Math.abs 避免浮点数精度问题)
        if (Math.abs(limiter.getRate() - qps) > 0.0001) {
            limiter.setRate(qps);
        }
        // 立刻拿令牌，不等待
        return limiter.tryAcquire();
    }
}