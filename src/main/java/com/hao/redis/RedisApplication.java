package com.hao.redis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * Redis学习项目启动类
 *
 * 设计目的：
 * 1. 作为Spring Boot应用的标准化入口，负责初始化Spring容器与运行环境。
 * 2. 显式开启缓存注解支持(@EnableCaching)，为系统提供统一的缓存切面能力。
 *
 * 核心实现思路：
 * - 组合 @SpringBootApplication 注解实现自动配置与组件扫描。
 * - 注入 @EnableCaching 激活Spring Cache抽象层，允许通过注解操作Redis。
 */
@SpringBootApplication
@EnableCaching
public class RedisApplication {

    /**
     * 应用主入口方法
     *
     * 实现逻辑：
     * 1. 委托SpringApplication类进行应用引导。
     * 2. 加载类路径下的配置并启动内嵌Web容器。
     * 3. 初始化ApplicationContext上下文环境。
     *
     * @param args 命令行传入参数
     */
    public static void main(String[] args) {
        // 实现思路：
        // 1. 启动Spring应用上下文，完成Bean的加载与依赖注入。
        SpringApplication.run(RedisApplication.class, args);
    }
}
