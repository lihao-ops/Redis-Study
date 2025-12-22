package com.hao.redis.common.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 单机限流注解
 *
 * 注解职责：
 * 标记需要进行限流的方法，并提供限流参数配置。
 *
 * 设计目的：
 * 1. 声明式编程：通过注解即可为接口开启限流，无需侵入业务代码。
 * 2. 灵活配置：支持为不同接口配置不同的限流阈值与模式。
 *
 * 为什么需要该注解：
 * 注解是实现 AOP 的最佳载体，便于切面精准定位目标方法。
 *
 * 核心实现思路：
 * - 定义限流模式、QPS、消息等属性。
 * - 切面通过反射读取注解属性以执行相应限流策略。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SimpleRateLimit {

    /**
     * 限流模式
     */
    enum LimitType {
        /**
         * 单机限流（默认）
         */
        STANDALONE,
        /**
         * 分布式限流
         */
        DISTRIBUTED
    }

    /**
     * 限流模式，默认单机
     */
    LimitType type() default LimitType.STANDALONE;

    /**
     * 接口级的 QPS 阈值。
     * 支持 SpEL 表达式从配置文件读取，如 "${rate-limit.api.default-qps}"。
     */
    String qps() default "100.0";

    /**
     * 用户级的 QPS 阈值。
     * 用于防止单个用户恶意攻击，通常设置得比接口级 QPS 低。
     */
    double userQps() default 5.0;

    /**
     * 限流后返回的提示信息
     */
    String message() default "系统繁忙，请稍后重试";

    /**
     * 限流键，默认为空。
     * 为空时，自动使用请求的匹配路径作为键。
     * 建议在路径包含变量时（如 /users/{id}）显式指定，以保证限流键的唯一性。
     */
    String key() default "";
}
