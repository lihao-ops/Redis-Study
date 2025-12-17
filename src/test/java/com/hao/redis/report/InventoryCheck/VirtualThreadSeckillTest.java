package com.hao.redis.report.InventoryCheck;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore; // 引入信号量
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 虚拟线程 Redis 集群分片秒杀压测 (终极优化版)
 * <p>
 * 优化点：
 * 1. 修复库存初始化精度丢失问题
 * 2. 引入 Semaphore 客户端流控，防止 Windows 端口耗尽
 * 3. 加大请求量以预热 JVM
 */
@Slf4j
@SpringBootTest
public class VirtualThreadSeckillTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    @Qualifier("virtualThreadExecutor")
    private Executor virtualThreadExecutor;

    private DefaultRedisScript<Long> deductStockScript;

    // --- ⚔️ 终极压测参数配置 ⚔️ ---
    private static final String PRODUCT_KEY_PREFIX = "seckill:product:9999:";

    // 【优化1】分片数：60 (3台机器每台分20个，均衡负载)
    private static final int SHARD_COUNT = 60;

    // 【优化2】直接定义单片库存，避免除法余数丢失
    private static final int STOCK_PER_SHARD = 5000;

    // 动态计算总库存：60 * 5000 = 300,000
    private static final int TOTAL_INITIAL_STOCK = SHARD_COUNT * STOCK_PER_SHARD;

    // 【优化3】请求量：50万 (让压测持续40秒以上，测出真实性能)
    private static final int TOTAL_REQUESTS = 500000;

    // 【优化4】客户端最大并发限制 (防止本机报错)
    // 【修改】客户端最大并发限制：从 2000 降为 500
    // 目的：防止 Windows 端口耗尽和连接池排队超时
    private static final int MAX_CONCURRENT_REQUESTS = 800;

    @BeforeEach
    public void setup() {
        // 1. 定义 Lua 脚本
        String scriptText =
                "if (redis.call('get', KEYS[1]) == false) then return -1 end; " +
                        "local stock = tonumber(redis.call('get', KEYS[1])); " +
                        "if (stock > 0) then " +
                        "   redis.call('decr', KEYS[1]); " +
                        "   return 1; " +
                        "else " +
                        "   return 0; " +
                        "end";

        deductStockScript = new DefaultRedisScript<>();
        deductStockScript.setScriptText(scriptText);
        deductStockScript.setResultType(Long.class);

        // 2. 初始化 Redis 数据
        log.info("🔨 初始化 {} 个分片，单片库存: {}，总库存: {}", SHARD_COUNT, STOCK_PER_SHARD, TOTAL_INITIAL_STOCK);
        for (int i = 0; i < SHARD_COUNT; i++) {
            String shardKey = PRODUCT_KEY_PREFIX + i;
            stringRedisTemplate.delete(shardKey);
            stringRedisTemplate.opsForValue().set(shardKey, String.valueOf(STOCK_PER_SHARD));
        }

        // 3. 强力预热
        try {
            log.info("🔌 全分片连接预热中...");
            for (int i = 0; i < SHARD_COUNT; i++) {
                String shardKey = PRODUCT_KEY_PREFIX + i;
                stringRedisTemplate.execute(deductStockScript, Collections.singletonList(shardKey));
                stringRedisTemplate.opsForValue().increment(shardKey);
            }
            log.info("🔥 预热完成 | 准备起飞");
        } catch (Exception e) {
            log.warn("预热异常: {}", e.getMessage());
        }
    }

    @Test
    public void benchmarkSharding() throws InterruptedException {
        CountDownLatch endLatch = new CountDownLatch(TOTAL_REQUESTS);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicBoolean isIdentityChecked = new AtomicBoolean(false);

        // 【关键】流控信号量：只有拿到令牌的线程才能发请求
        Semaphore limiter = new Semaphore(MAX_CONCURRENT_REQUESTS);

        log.info("🚀 --- [终极压测] 提交 {} 个任务 (本机并发限制: {}) ---", TOTAL_REQUESTS, MAX_CONCURRENT_REQUESTS);
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < TOTAL_REQUESTS; i++) {
            virtualThreadExecutor.execute(() -> {
                try {
                    // 1. 获取令牌 (阻塞等待，不会报错)
                    limiter.acquire();

                    // 身份查验
                    if (isIdentityChecked.compareAndSet(false, true)) {
                        log.info("🕵️‍♂️ [抽样] 当前线程: {} | Virtual: {}", Thread.currentThread(), Thread.currentThread().isVirtual());
                    }

                    int shardIndex = ThreadLocalRandom.current().nextInt(SHARD_COUNT);
                    String targetKey = PRODUCT_KEY_PREFIX + shardIndex;

                    Long result = stringRedisTemplate.execute(
                            deductStockScript,
                            Collections.singletonList(targetKey)
                    );

                    if (result != null && result == 1) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }

                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    if (errorCount.get() <= 5) log.error("异常: {}", e.getMessage());
                } finally {
                    // 2. 释放令牌
                    limiter.release();
                    endLatch.countDown();
                }
            });
        }

        endLatch.await();
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        if (duration == 0) duration = 1;

        // --- 📊 结果统计 ---
        double tps = (double) TOTAL_REQUESTS / duration * 1000;

        // 校验逻辑
        long totalRemainingStock = 0;
        for (int i = 0; i < SHARD_COUNT; i++) {
            String shardKey = PRODUCT_KEY_PREFIX + i;
            String val = stringRedisTemplate.opsForValue().get(shardKey);
            if (val != null) {
                totalRemainingStock += Long.parseLong(val);
            }
        }
        long expectedRemaining = TOTAL_INITIAL_STOCK - successCount.get();

        log.info("🛑 --- 压测结束 ---");
        log.info("耗时: {} ms (约 {} 秒)", duration, duration / 1000);
        log.info("⚡️ TPS: {}", String.format("%.2f", tps));
        log.info("统计 -> 总数: {}, 成功: {}, 失败: {}, 异常: {}",
                TOTAL_REQUESTS, successCount.get(), failCount.get(), errorCount.get());
        log.info("校验 -> 初始: {}, 剩余: {}, 理论: {}",
                TOTAL_INITIAL_STOCK, totalRemainingStock, expectedRemaining);

        if (totalRemainingStock == expectedRemaining) {
            log.info("✅ [通过] 完美！数据一致。");
        } else {
            log.error("❌ [失败] 数据不一致！");
            throw new RuntimeException("库存校验失败");
        }
    }

    @AfterEach
    public void tearDown() {
        log.info("🧹 清理数据...");
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < SHARD_COUNT; i++) {
            keys.add(PRODUCT_KEY_PREFIX + i);
        }
        stringRedisTemplate.delete(keys);

        try {
            stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
                connection.scriptingCommands().scriptFlush();
                return null;
            });
        } catch (Exception e) {}
    }
}