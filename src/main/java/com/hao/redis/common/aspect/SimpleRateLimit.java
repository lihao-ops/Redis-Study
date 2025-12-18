package com.hao.redis.common.aspect;

import java.lang.annotation.*;

/**
 * 接口限流注解
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SimpleRateLimit {

    /**
     * 每秒允许的请求数（支持 ${property} 格式或直接数字）
     */
    String qps() default "100";
    
    /**
     * 限流提示
     */
    String message() default "请求过于频繁，请稍后重试";

    /**
     * 限流类型
     */
    LimitType type() default LimitType.STANDALONE;

    enum LimitType {
        /** 单机限流 (Guava RateLimiter) */
        STANDALONE,
        /** 分布式限流 (Redis + Lua) + 单机兜底 */
        DISTRIBUTED
    }
}