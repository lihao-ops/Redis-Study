package com.hao.redis.common.util;

import com.hao.redis.integration.redis.RedisClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 缓存雪崩防护测试类
 * <p>
 * 测试目的：
 * 1. 【反面教材】演示固定 TTL 导致的大量 Key 同时过期（雪崩前兆）。
 * 2. 【正面教材】验证随机 TTL (Jitter) 能有效分散过期时间，避免集体失效。
 * <p>
 * 设计思路：
 * - 批量写入大量 Key。
 * - 监控 Key 在过期时间点附近的存活数量变化曲线。
 */
@Slf4j
@SpringBootTest
public class CacheAvalancheTest {

    @Autowired
    private RedisClient<String> redisClient;

    private static final String KEY_PREFIX = "test:avalanche:";
    private static final int KEY_COUNT = 1000; // 模拟 1000 个热点 Key
    private static final int BASE_TTL = 5; // 基础过期时间 5 秒

    /**
     * 测试前清理
     * 防止上次异常中断残留数据影响本次测试
     */
    @BeforeEach
    public void setUp() {
        cleanRedisData();
    }

    /**
     * 测试后清理
     * 确保不污染环境
     */
    @AfterEach
    public void tearDown() {
        cleanRedisData();
    }

    private void cleanRedisData() {
        // 批量删除测试 Key
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < KEY_COUNT; i++) {
            keys.add(KEY_PREFIX + i);
        }
        // 分批删除，避免一次性传太多参数导致 Redis 报错（虽然 1000 个通常没问题）
        // 这里简单处理，直接删
        if (!keys.isEmpty()) {
            redisClient.del(keys.toArray(new String[0]));
        }
        log.info("Redis 测试数据已清理|Redis_data_cleaned");
    }

    /**
     * 场景一：固定 TTL 导致的集体过期（模拟雪崩）
     * <p>
     * 预期结果：
     * 在 TTL 到达的那一秒，存活 Key 数量从 1000 瞬间变为 0（断崖式下跌）。
     */
    @Test
    @DisplayName("反面教材：固定TTL导致集体过期")
    public void testFixedTtl_Avalanche() throws InterruptedException {
        log.info(">>> 开始测试：固定 TTL 写入 {} 个 Key，TTL={}s", KEY_COUNT, BASE_TTL);

        // 1. 批量写入，TTL 固定为 5 秒
        for (int i = 0; i < KEY_COUNT; i++) {
            redisClient.setex(KEY_PREFIX + i, BASE_TTL, "value");
        }

        // 2. 监控过期过程
        monitorExpiration("固定TTL");
    }

    /**
     * 场景二：随机 TTL 分散过期时间（预防雪崩）
     * <p>
     * 预期结果：
     * 在 TTL 到达后，Key 是陆续过期的，存活数量缓慢下降（线性下滑）。
     */
    @Test
    @DisplayName("正面教材：随机TTL分散过期压力")
    public void testRandomTtl_Protection() throws InterruptedException {
        log.info(">>> 开始测试：随机 TTL 写入 {} 个 Key，基础TTL={}s", KEY_COUNT, BASE_TTL);

        // 1. 批量写入，使用随机 TTL
        for (int i = 0; i < KEY_COUNT; i++) {
            redisClient.setWithRandomTtl(KEY_PREFIX + i, "value", BASE_TTL, TimeUnit.SECONDS);
        }

        // 2. 监控过期过程
        monitorExpiration("随机TTL");
    }

    /**
     * 监控 Key 过期过程的辅助方法
     */
    private void monitorExpiration(String sceneName) throws InterruptedException {
        // 初始检查
        long initialCount = countAliveKeys();
        Assertions.assertEquals(KEY_COUNT, initialCount, "写入失败，初始数量不对");
        log.info("[{}] 初始存活 Key 数量: {}", sceneName, initialCount);

        // 开始倒计时监控
        // 我们从第 3 秒开始监控，一直到第 8 秒（覆盖 5 秒这个过期点）
        Thread.sleep(4000); // 休眠 4 秒

        log.info(">>> [{}] 进入过期高发区...", sceneName);
        
        for (int i = 1; i <= 30; i++) { // 监控 3 秒，每 100ms 一次
            Thread.sleep(100);
            long alive = countAliveKeys();
            log.info("[{}] Time={}ms, 存活 Key: {}", sceneName, 4000 + i * 100, alive);
            
            if (alive == 0) {
                log.info(">>> [{}] 所有 Key 已过期", sceneName);
                break;
            }
        }
    }

    private long countAliveKeys() {
        // 简单粗暴：遍历查询（仅用于测试，生产环境严禁这样写）
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < KEY_COUNT; i++) {
            keys.add(KEY_PREFIX + i);
        }
        
        List<String> values = redisClient.mget(keys.toArray(new String[0]));
        return values.stream().filter(v -> v != null).count();
    }
}
