package com.hao.redis.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * Redis分布式限流工具类
 *
 * 类职责：
 * 提供基于Redis + Lua脚本的分布式限流能力，确保高并发场景下的系统稳定性。
 *
 * 设计目的：
 * 1. 保证操作原子性：使用Lua脚本封装“INCR+EXPIRE+校验”逻辑，避免并发竞态。
 * 2. 降低网络开销：将多次Redis交互合并为一次网络请求。
 * 3. 提供容错机制：当Redis不可用时，自动降级为“允许通过”，防止限流组件成为系统瓶颈。
 *
 * 为什么需要该类：
 * 分布式限流必须具备原子性与高可用特性，独立封装可降低业务侵入。
 *
 * 实现思路：
 * - 采用计数器固定窗口算法。
 * - 通过 Lua 脚本在 Redis 端原子执行计数与过期时间设置。
 * - 捕获 Redis 执行异常，默认执行故障放行策略。
 */
@Slf4j
@Component
public class RedisRateLimiter {

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> limitScript;

    // Lua 脚本：固定窗口计数器
    // KEYS[1]: 限流键
    // ARGV[1]: 限流阈值
    // ARGV[2]: 时间窗口(秒)
    private static final String LUA_SCRIPT_TEXT =
            "local key = KEYS[1] " +
            "local limit = tonumber(ARGV[1]) " +
            "local window = tonumber(ARGV[2]) " +
            "local current = redis.call('INCR', key) " +
            "if current == 1 then " +
            "    redis.call('EXPIRE', key, window) " +
            "end " +
            "if current > limit then " +
            "    return 0 " +
            "end " +
            "return 1";

    /**
     * Redis 限流器构造方法
     *
     * 实现逻辑：
     * 1. 注入 StringRedisTemplate。
     * 2. 初始化 Lua 脚本并设置返回类型。
     *
     * @param stringRedisTemplate Redis 模板
     */
    public RedisRateLimiter(StringRedisTemplate stringRedisTemplate) {
        // 实现思路：
        // 1. 注入模板并完成脚本初始化。
        this.stringRedisTemplate = stringRedisTemplate;
        // 初始化 Lua 脚本
        this.limitScript = new DefaultRedisScript<>();
        this.limitScript.setScriptText(LUA_SCRIPT_TEXT);
        this.limitScript.setResultType(Long.class);
    }

    /**
     * 尝试获取访问许可
     *
     * 实现逻辑：
     * 1. 构造 Redis 键。
     * 2. 执行 Lua 脚本进行原子计数校验。
     * 3. 处理 Redis 异常，执行降级策略。
     *
     * @param key 业务键（如用户ID、IP）
     * @param limit 限制次数
     * @param windowSeconds 时间窗口（秒）
     * @return true表示允许访问，false表示拒绝访问
     */
    public boolean tryAcquire(String key, int limit, int windowSeconds) {
        // 实现思路：
        // 1. 构造 Redis 键。
        // 2. 执行 Lua 脚本进行原子计数校验。
        // 3. 处理 Redis 异常，执行降级策略。

        // 构造完整的 Redis 键，增加前缀避免冲突
        String redisKey = "rate_limit:" + key;

        try {
            // 核心代码：执行 Lua 脚本，原子性判断是否限流
            // 参数说明：KEYS=[限流键], ARGV=[阈值, 窗口秒数]
            Long result = stringRedisTemplate.execute(
                    limitScript,
                    Collections.singletonList(redisKey),
                    String.valueOf(limit),
                    String.valueOf(windowSeconds)
            );

            // Lua脚本返回1表示允许，0表示拒绝
            return result != null && result == 1L;

        } catch (Exception e) {
            // 实现思路：
            // 1. 捕获 Redis 连接超时、读写失败等异常。
            // 2. 记录错误日志，保留现场信息。
            // 3. 执行故障放行策略（返回true），优先保证业务可用性，牺牲部分限流保护。
            log.error("Redis限流服务异常_触发自动降级_允许通过|Redis_limiter_error_fallback_allow,key={},error={}", key, e.getMessage(), e);
            return true;
        }
    }
}
