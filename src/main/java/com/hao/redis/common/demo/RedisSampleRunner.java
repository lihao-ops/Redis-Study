package com.hao.redis.common.demo;

import com.hao.redis.integration.redis.RedisClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Redis 启动示例执行器
 *
 * 类职责：
 * 在应用启动时执行简单读写，用于验证 Redis 客户端可用性。
 *
 * 设计目的：
 * 1. 提供轻量级启动自检能力。
 * 2. 便于本地或测试环境快速验证配置。
 *
 * 为什么需要该类：
 * Redis 连接异常会影响核心业务，启动自检有助于提前发现问题。
 *
 * 核心实现思路：
 * - 使用 CommandLineRunner 在启动后执行示例操作。
 * - 通过配置项控制是否启用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "redis.demo.enabled", havingValue = "true")
public class RedisSampleRunner implements CommandLineRunner {

    private final RedisClient<String> redisClient;

    @Override
    public void run(String... args) {
        // 实现思路：
        // 1. 写入示例数据。
        // 2. 读取并输出结果用于验证。
        String demoKey = "demo:hello";
        redisClient.set(demoKey, "world", 30);
        String value = redisClient.get(demoKey);
        Long ttl = redisClient.ttl(demoKey);

        redisClient.hmset("demo:hash", Map.of("name", "redis", "version", "7"));
        var hash = redisClient.hgetAll("demo:hash");

        log.info("Redis示例值|Redis_demo_value,key={},value={},ttlSeconds={}", demoKey, value, ttl);
        log.info("Redis示例哈希|Redis_demo_hash,hash={}", hash);
    }
}
