package com.hao.redis.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * Redis分布式限流工具类
 *
 * 类职责：
 * 提供基于Redis + Lua脚本的分布式限流能力，确保高并发场景下的系统稳定性。
 *
 * 设计目的：
 * 1. 保证操作原子性：使用Lua脚本封装 "INCR + EXPIRE + CHECK" 逻辑，避免并发竞态。
 * 2. 降低网络开销：将多次Redis交互合并为一次网络请求。
 * 3. 提供容错机制：当Redis不可用时，自动降级为“允许通过”，防止限流组件成为系统瓶颈。
 *
 * 实现思路：
 * - 采用计数器固定窗口算法（Fixed Window Counter）。
 * - 通过 Lua 脚本在 Redis 端原子执行计数与过期时间设置。
 * - 捕获 Redis 执行异常，默认执行“Fail-Open”策略。
 */
@Slf4j
@Component
public class RedisRateLimiter {

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> limitScript;

    public RedisRateLimiter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        // 初始化 Lua 脚本
        this.limitScript = new DefaultRedisScript<>();
        this.limitScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/rate_limiter.lua")));
        this.limitScript.setResultType(Long.class);
    }

    /**
     * 尝试获取访问许可
     *
     * 实现逻辑：
     * 1. 构造 Redis Key。
     * 2. 执行 Lua 脚本进行原子计数校验。
     * 3. 处理 Redis 异常，执行降级策略。
     *
     * @param key 业务键（如 userId, ip）
     * @param limit 限制次数
     * @param windowSeconds 时间窗口（秒）
     * @return true-允许访问, false-拒绝访问
     */
    public boolean tryAcquire(String key, int limit, int windowSeconds) {
        // 实现思路：
        // 1. 构造 Redis Key。
        // 2. 执行 Lua 脚本进行原子计数校验。
        // 3. 处理 Redis 异常，执行降级策略。

        // 构造完整的 Redis Key，增加前缀避免冲突
        String redisKey = "rate_limit:" + key;

        try {
            // 执行 Lua 脚本，原子性判断是否限流
            // 参数说明：KEYS=[key], ARGV=[limit, windowSeconds]
            Long result = stringRedisTemplate.execute(
                    limitScript,
                    Collections.singletonList(redisKey),
                    String.valueOf(limit),
                    String.valueOf(windowSeconds)
            );

            // Lua脚本返回 1 代表允许，0 代表拒绝
            return result != null && result == 1L;

        } catch (Exception e) {
            // 实现思路：
            // 1. 捕获 Redis 连接超时、读写失败等异常。
            // 2. 记录 ERROR 日志，保留现场信息。
            // 3. 执行 Fail-Open 策略（返回 true），优先保证业务可用性，牺牲部分限流保护。
            log.error("Redis限流服务异常_触发自动降级_允许通过|Redis_limiter_error_fallback_allow,key={},error={}", key, e.getMessage(), e);
            return true;
        }
    }
}