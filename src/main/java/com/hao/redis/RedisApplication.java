package com.hao.redis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Redis 学习项目启动入口
 *
 * 类职责：
 * 负责引导 Spring Boot 应用启动与组件扫描。
 *
 * 设计目的：
 * 1. 统一应用启动入口，便于部署与运行管理。
 * 2. 显式启用缓存能力，支撑 Redis 场景的统一缓存切面。
 *
 * 为什么需要该类：
 * Spring Boot 需要稳定的主入口来加载配置并初始化应用上下文。
 *
 * 核心实现思路：
 * - 组合 @SpringBootApplication 完成自动配置与组件扫描。
 * - 组合 @EnableCaching 启用缓存注解能力。
 * - 组合 @EnableScheduling 启用定时任务能力。
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling // 启用定时任务
public class RedisApplication {

    /**
     * 应用主入口
     *
     * 实现逻辑：
     * 1. 交由 SpringApplication 启动 Spring 上下文。
     * 2. 完成自动配置、组件扫描与依赖注入初始化。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        // 实现思路：
        // 1. 启动 Spring 容器并完成自动配置加载。
        // 核心启动入口：触发 Spring Boot 应用启动
        SpringApplication.run(RedisApplication.class, args);
    }
}
