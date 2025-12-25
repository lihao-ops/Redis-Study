package com.hao.redis.integration.lock;

import com.hao.redis.integration.redis.RedisClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 基于 Redis 的分布式锁服务实现
 */
@Service
public class RedisDistributedLockService implements DistributedLockService {

    private final RedisClient<String> redisClient;
    private final StringRedisTemplate stringRedisTemplate;

    // 从配置文件读取看门狗超时时间，默认 30 秒
    @Value("${distributed.lock.watchdog.timeout:30000}")
    private long lockWatchdogTimeout;

    public RedisDistributedLockService(RedisClient<String> redisClient, StringRedisTemplate stringRedisTemplate) {
        this.redisClient = redisClient;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public DistributedLock getLock(String name) {
        String lockKey = "lock:" + name;
        return new RedisDistributedLock(lockKey, redisClient, stringRedisTemplate, lockWatchdogTimeout);
    }
}
