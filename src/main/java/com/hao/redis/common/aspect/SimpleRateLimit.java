package com.hao.redis.common.aspect;

import java.lang.annotation.*;

/**
 * 接口限流注解
 *
 * 类职责：
 * 声明接口或方法级别的限流策略与阈值。
 *
 * 设计目的：
 * 1. 通过注解实现限流配置与业务逻辑解耦。
 * 2. 支持单机与分布式两级限流策略组合。
 *
 * 为什么需要该类：
 * 限流配置需要统一入口与可读性，注解是最轻量的声明方式。
 *
 * 核心实现思路：
 * - 由切面解析注解元数据并执行限流判断。
 * - 通过注解参数绑定不同限流策略与提示信息。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SimpleRateLimit {

    /**
     * 每秒允许的请求数（支持 ${property} 格式或直接数字）
     *
     * 实现逻辑：
     * 1. 作为元数据提供 QPS 配置给切面解析。
     *
     * @return QPS 配置
     */
    String qps() default "100";
    
    /**
     * 限流提示
     *
     * 实现逻辑：
     * 1. 作为元数据提供限流提示给切面与异常处理。
     *
     * @return 限流提示语
     */
    String message() default "请求过于频繁，请稍后重试";

    /**
     * 限流类型
     *
     * 实现逻辑：
     * 1. 作为元数据提供限流策略给切面执行。
     *
     * @return 限流类型
     */
    LimitType type() default LimitType.STANDALONE;

    enum LimitType {
        /** 单机限流（Guava RateLimiter） */
        STANDALONE,
        /** 分布式限流（Redis + Lua）+ 单机兜底 */
        DISTRIBUTED
    }
}
