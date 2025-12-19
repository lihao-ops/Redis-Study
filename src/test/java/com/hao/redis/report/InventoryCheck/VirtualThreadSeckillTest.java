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
 * 虚拟线程 Redis 集群分片秒杀压测
 *
 * 类职责：
 * 验证虚拟线程在分片库存扣减场景下的吞吐与一致性。
 *
 * 测试目的：
 * 1. 验证 Lua 脚本原子扣减的正确性。
 * 2. 验证高并发下库存一致性与错误率。
 *
 * 设计思路：
 * - 使用分片库存分散热点。
 * - 使用 Semaphore 控制客户端并发。
 * - 使用虚拟线程执行请求提升并发度。
 *
 * 为什么需要该类：
 * 秒杀场景对一致性与吞吐要求极高，需要压测验证架构承压能力。
 *
 * 核心实现思路：
 * - 初始化分片库存并预热。
 * - 并发执行扣减并统计结果。
 * - 汇总校验库存一致性并清理数据。
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

    // --- 压测参数配置 ---
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

    /**
     * 测试前置初始化
     *
     * 实现逻辑：
     * 1. 初始化扣减库存的 Lua 脚本。
     * 2. 写入分片库存并执行预热。
     */
    @BeforeEach
    public void setup() {
        // 实现思路：
        // 1. 初始化脚本与库存。
        // 2. 预热连接与脚本缓存。
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
        log.info("初始化分片库存|Init_shards,shardCount={},stockPerShard={},totalStock={}",
                SHARD_COUNT, STOCK_PER_SHARD, TOTAL_INITIAL_STOCK);
        for (int i = 0; i < SHARD_COUNT; i++) {
            String shardKey = PRODUCT_KEY_PREFIX + i;
            stringRedisTemplate.delete(shardKey);
            stringRedisTemplate.opsForValue().set(shardKey, String.valueOf(STOCK_PER_SHARD));
        }

        // 3. 强力预热
        try {
            log.info("分片连接预热|Shard_warmup_start");
            for (int i = 0; i < SHARD_COUNT; i++) {
                String shardKey = PRODUCT_KEY_PREFIX + i;
                stringRedisTemplate.execute(deductStockScript, Collections.singletonList(shardKey));
                stringRedisTemplate.opsForValue().increment(shardKey);
            }
            log.info("预热完成|Warmup_done");
        } catch (Exception e) {
            log.warn("预热异常|Warmup_error,message={}", e.getMessage());
        }
    }

    /**
     * 分片秒杀压测执行
     *
     * 实现逻辑：
     * 1. 使用虚拟线程并发执行扣减。
     * 2. 汇总成功、失败、异常并校验库存一致性。
     *
     * @throws InterruptedException 线程中断异常
     */
    @Test
    public void benchmarkSharding() throws InterruptedException {
        // 实现思路：
        // 1. 并发执行扣减并统计结果。
        CountDownLatch endLatch = new CountDownLatch(TOTAL_REQUESTS);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicBoolean isIdentityChecked = new AtomicBoolean(false);

        // 【关键】流控信号量：只有拿到令牌的线程才能发请求
        Semaphore limiter = new Semaphore(MAX_CONCURRENT_REQUESTS);

        log.info("压测任务提交|Stress_task_submit,totalRequests={},maxConcurrent={}",
                TOTAL_REQUESTS, MAX_CONCURRENT_REQUESTS);
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < TOTAL_REQUESTS; i++) {
            virtualThreadExecutor.execute(() -> {
                try {
                    // 1. 获取令牌 (阻塞等待，不会报错)
                    limiter.acquire();

                    // 身份查验
                    if (isIdentityChecked.compareAndSet(false, true)) {
                        log.info("线程抽样|Thread_sample,thread={},isVirtual={}",
                                Thread.currentThread(), Thread.currentThread().isVirtual());
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
                    if (errorCount.get() <= 5) log.error("请求异常|Request_error,message={}", e.getMessage());
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

        // --- 结果统计 ---
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

        log.info("压测结束|Stress_done");
        log.info("耗时|Duration_ms,value={},seconds={}", duration, duration / 1000);
        log.info("吞吐量|Throughput_tps,value={}", String.format("%.2f", tps));
        log.info("结果统计|Result_summary,total={},success={},fail={},error={}",
                TOTAL_REQUESTS, successCount.get(), failCount.get(), errorCount.get());
        log.info("库存校验|Stock_check,initial={},remaining={},expected={}",
                TOTAL_INITIAL_STOCK, totalRemainingStock, expectedRemaining);

        if (totalRemainingStock == expectedRemaining) {
            log.info("校验通过|Check_passed");
        } else {
            log.error("校验失败|Check_failed");
            throw new RuntimeException("库存校验失败");
        }
    }

    /**
     * 测试后置清理
     *
     * 实现逻辑：
     * 1. 删除分片库存数据。
     * 2. 清理脚本缓存。
     */
    @AfterEach
    public void tearDown() {
        // 实现思路：
        // 1. 清理 Redis 数据与脚本缓存。
        log.info("清理数据|Cleanup_start");
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
