package com.hao.redis.report.limit;

import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单机令牌桶算法测试
 *
 * 类职责：
 * 验证 Guava RateLimiter 的限流行为与性能指标。
 *
 * 测试目的：
 * 1. 验证令牌生成速率是否符合预期。
 * 2. 模拟全局限流与接口限流的组合场景。
 * 3. 评估本机限流检查的极限吞吐。
 *
 * 设计思路：
 * - 通过 acquire 验证平滑速率。
 * - 通过并发模拟验证组合限流效果。
 * - 通过高频调用评估吞吐能力。
 *
 * 为什么需要该类：
 * 单机限流是第一道防线，需要用基准测试验证可用性与性能边界。
 *
 * 核心实现思路：
 * - 分基础验证、组合场景与性能测试三个阶段执行。
 */
@Slf4j
@SpringBootTest
public class GuavaRateLimiterTest {

    /**
     * 基础令牌桶功能验证
     *
     * 实现逻辑：
     * 1. 创建限流器并连续获取令牌。
     * 2. 统计耗时并断言速率。
     */
    @Test
    @DisplayName("基础功能：验证令牌桶生成速率 (QPS=5)")
    public void testTokenBucketBasic() {
        // 实现思路：
        // 1. 通过连续获取令牌验证平滑速率。
        // 创建每秒生成5个令牌的限流器（每200毫秒一个）
        double qps = 5.0;
        RateLimiter limiter = RateLimiter.create(qps);

        log.info("基础令牌桶测试开始|Token_bucket_basic_start,qps={}", qps);
        log.info("令牌桶预热|Token_bucket_warmup,waitSeconds={}", limiter.acquire());

        long start = System.nanoTime();
        // 尝试连续获取 10 个令牌
        for (int i = 1; i <= 10; i++) {
            // 获取令牌会阻塞直到成功，并返回等待时间
            double waitTime = limiter.acquire();
            log.info("获取令牌耗时|Token_acquire_latency,seq={},waitSeconds={}", i, String.format("%.4f", waitTime));
        }
        long end = System.nanoTime();
        double totalTimeMs = (end - start) / 1_000_000.0;

        log.info("令牌获取总耗时|Token_acquire_total,costMs={}", totalTimeMs);

        // 简单断言：阈值为5时，理论耗时约2秒，允许一定误差。
        Assertions.assertTrue(totalTimeMs > 1800 && totalTimeMs < 2200, "令牌生成速率偏差过大");
    }

    /**
     * 全局限流与接口限流组合场景
     *
     * 实现逻辑：
     * 1. 构建全局与接口级限流器。
     * 2. 并发发起请求统计成功与拒绝数量。
     * 3. 校验限流触发顺序是否符合预期。
     *
     * @throws InterruptedException 线程中断异常
     */
    @Test
    @DisplayName("场景模拟：全局限流(50) vs 接口限流(10)")
    public void testGlobalAndInterfaceLimit() throws InterruptedException {
        // 实现思路：
        // 1. 并发发起请求，观察拒绝分布。
        // 全局限流器
        RateLimiter globalLimiter = RateLimiter.create(50.0);
        // 特定接口限流器
        RateLimiter interfaceLimiter = RateLimiter.create(10.0);

        // 预热令牌桶：创建后需要时间生成令牌。
        // 睡眠 1 秒以确保令牌桶填满 (50个和10个)，从而支持突发流量。
        Thread.sleep(1000);

        int clientCount = 20; // 模拟20个并发请求
        ExecutorService executor = Executors.newFixedThreadPool(clientCount);
        CountDownLatch latch = new CountDownLatch(clientCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger globalRejectCount = new AtomicInteger(0);
        AtomicInteger interfaceRejectCount = new AtomicInteger(0);

        log.info("并发测试开始|Concurrent_test_start,clientCount={}", clientCount);

        for (int i = 0; i < clientCount; i++) {
            executor.submit(() -> {
                try {
                    // 1. 先检查全局限流（非阻塞获取令牌）
                    if (globalLimiter.tryAcquire()) {
                        // 2. 再检查接口限流
                        if (interfaceLimiter.tryAcquire()) {
                            // 业务处理
                            successCount.incrementAndGet();
                            log.info("请求成功|Request_success");
                        } else {
                            interfaceRejectCount.incrementAndGet();
                            log.warn("接口限流拒绝|Interface_rate_limited");
                        }
                    } else {
                        globalRejectCount.incrementAndGet();
                        log.error("全局限流拒绝|Global_rate_limited");
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        log.info("测试结果统计|Test_result_summary");
        log.info("总请求数|Total_requests,count={}", clientCount);
        log.info("成功处理|Success_count,count={}", successCount.get());
        log.info("接口限流拒绝|Interface_limited_count,count={}", interfaceRejectCount.get());
        log.info("全局限流拒绝|Global_limited_count,count={}", globalRejectCount.get());

        // 验证逻辑：突发请求下接口限流应拒绝部分请求
        // 全局限流阈值较大，预期不应拒绝请求
        Assertions.assertEquals(0, globalRejectCount.get(), "全局限流器不应拦截，因为总请求数小于全局阈值");
        Assertions.assertTrue(interfaceRejectCount.get() > 0, "接口限流器应该拦截部分突发请求");
    }

    /**
     * 本机性能基准测试
     *
     * 实现逻辑：
     * 1. 高并发调用 tryAcquire 统计吞吐。
     * 2. 输出平均耗时与性能等级。
     */
    @Test
    @DisplayName("性能测试：本机限流器极限吞吐量 (OPS)")
    public void testPerformance() {
        // 实现思路：
        // 1. 高次数调用测量吞吐与耗时。
        // 创建一个极大容量的限流器，确保不会阻塞，只测逻辑开销
        RateLimiter limiter = RateLimiter.create(5_000_000.0);

        int iterations = 1_000_000; // 一百万次调用
        log.info("性能压测开始|Performance_test_start,iterations={}", iterations);

        // 预热运行时
        for (int i = 0; i < 10000; i++) limiter.tryAcquire();

        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            limiter.tryAcquire();
        }
        long end = System.nanoTime();

        long durationNs = end - start;
        double durationMs = durationNs / 1_000_000.0;
        double ops = (iterations * 1_000_000_000.0) / durationNs;

        log.info("压测完成|Performance_test_done");
        log.info("总耗时|Total_duration,ms={}", String.format("%.2f", durationMs));
        log.info("单次平均耗时|Avg_latency_ns,value={}", String.format("%.2f", (double) durationNs / iterations));
        log.info("本机极限吞吐|Max_ops_per_sec,opsTenThousand={}", String.format("%.2f", ops / 10000));

        // 性能评价输出
        if (ops > 10_000_000) {
            log.info("性能评价_极佳|Perf_level_excellent");
        } else if (ops > 1_000_000) {
            log.info("性能评价_良好|Perf_level_good");
        } else {
            log.warn("性能评价_一般|Perf_level_average");
        }
    }
}
