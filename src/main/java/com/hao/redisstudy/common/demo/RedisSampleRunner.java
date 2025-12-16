package com.hao.redisstudy.common.demo;

import com.hao.redisstudy.integration.redis.RedisClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 启动时的简单示例，用于验证 Redis 客户端是否可用。
 * 默认关闭，通过设置 redis.demo.enabled=true 启用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "redis.demo.enabled", havingValue = "true")
public class RedisSampleRunner implements CommandLineRunner {

    private final RedisClient<String> redisClient;

    @Override
    public void run(String... args) {
        String demoKey = "demo:hello";
        redisClient.set(demoKey, "world", 30);
        String value = redisClient.get(demoKey);
        Long ttl = redisClient.ttl(demoKey);

        redisClient.hmset("demo:hash", Map.of("name", "redis", "version", "7"));
        var hash = redisClient.hgetAll("demo:hash");

        log.info("Redis demo value key={} value={} ttlSeconds={}", demoKey, value, ttl);
        log.info("Redis demo hash={}", hash);
    }
}
