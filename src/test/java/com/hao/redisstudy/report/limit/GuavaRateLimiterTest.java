package com.hao.redisstudy.report.limit;

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
 * 单机令牌桶算法权威测试类
 * 基于 Guava RateLimiter 实现
 *
 * <p>测试目标：
 * 1. 验证令牌桶生成的速率是否符合预期（平滑限流）。
 * 2. 模拟全局限流与具体接口限流的组合场景。
 * 3. 压测本机处理限流检查的极限性能（OPS）。
 */
@Slf4j
@SpringBootTest
public class GuavaRateLimiterTest {

    /**
     * 测试 1: 基础令牌桶功能验证
     * 场景：限制 QPS 为 5，即每 200ms 生成一个令牌。
     * 预期：连续获取令牌时，耗时应平滑增加。
     */
    @Test
    @DisplayName("基础功能：验证令牌桶生成速率 (QPS=5)")
    public void testTokenBucketBasic() {
        // 创建一个每秒生成 5 个令牌的限流器 (每 200ms 一个)
        double qps = 5.0;
        RateLimiter limiter = RateLimiter.create(qps);

        log.info(">>> 开始基础令牌桶测试，QPS设定: {}", qps);
        log.info(">>> 预热：先获取一个令牌: {}", limiter.acquire());

        long start = System.nanoTime();
        // 尝试连续获取 10 个令牌
        for (int i = 1; i <= 10; i++) {
            // acquire() 会阻塞直到获取到令牌，返回等待时间
            double waitTime = limiter.acquire();
            log.info("第 {} 次获取令牌，等待耗时: {} 秒", i, String.format("%.4f", waitTime));
        }
        long end = System.nanoTime();
        double totalTimeMs = (end - start) / 1_000_000.0;

        log.info(">>> 获取 10 个令牌总耗时: {} ms (理论值应接近 2000ms)", totalTimeMs);

        // 简单断言：10个令牌，QPS=5，理论耗时2秒左右。允许一定误差。
        Assertions.assertTrue(totalTimeMs > 1800 && totalTimeMs < 2200, "令牌生成速率偏差过大");
    }

    /**
     * 测试 2: 模拟全局限流 + 接口级限流
     * 场景：
     * - 全局限流：50 QPS (保护整个系统)
     * - 接口A限流：10 QPS (保护特定热点接口)
     * - 模拟 20 个并发线程请求接口 A。
     * 预期：接口 A 的限流器先被触发，全局限流器未满。
     */
    @Test
    @DisplayName("场景模拟：全局限流(50) vs 接口限流(10)")
    public void testGlobalAndInterfaceLimit() throws InterruptedException {
        // 全局限流器
        RateLimiter globalLimiter = RateLimiter.create(50.0);
        // 特定接口限流器
        RateLimiter interfaceLimiter = RateLimiter.create(10.0);

        // 预热令牌桶：RateLimiter 创建时是空的，需要时间生成令牌。
        // 睡眠 1 秒以确保令牌桶填满 (50个和10个)，从而支持突发流量。
        Thread.sleep(1000);

        int clientCount = 20; // 模拟20个并发请求
        ExecutorService executor = Executors.newFixedThreadPool(clientCount);
        CountDownLatch latch = new CountDownLatch(clientCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger globalRejectCount = new AtomicInteger(0);
        AtomicInteger interfaceRejectCount = new AtomicInteger(0);

        log.info(">>> 开始并发测试：模拟 {} 个请求同时访问...", clientCount);

        for (int i = 0; i < clientCount; i++) {
            executor.submit(() -> {
                try {
                    // 1. 先检查全局限流 (非阻塞 tryAcquire)
                    if (globalLimiter.tryAcquire()) {
                        // 2. 再检查接口限流
                        if (interfaceLimiter.tryAcquire()) {
                            // 业务处理
                            successCount.incrementAndGet();
                            log.info("请求成功");
                        } else {
                            interfaceRejectCount.incrementAndGet();
                            log.warn("请求被 [接口限流] 拒绝");
                        }
                    } else {
                        globalRejectCount.incrementAndGet();
                        log.error("请求被 [全局限流] 拒绝");
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        log.info(">>> 测试结果统计：");
        log.info("总请求数: {}", clientCount);
        log.info("成功处理: {}", successCount.get());
        log.info("接口限流拒绝: {}", interfaceRejectCount.get());
        log.info("全局限流拒绝: {}", globalRejectCount.get());

        // 验证逻辑：因为是突发请求，接口限流器(10 QPS)应该会拒绝掉多余的请求
        // 而全局限流器(50 QPS)容量足够，不应该拒绝任何请求（除非Guava预热机制影响，但在SmoothBursty模式下通常不会）
        Assertions.assertEquals(0, globalRejectCount.get(), "全局限流器不应拦截，因为总请求数小于全局阈值");
        Assertions.assertTrue(interfaceRejectCount.get() > 0, "接口限流器应该拦截部分突发请求");
    }

    /**
     * 测试 3: 本机性能基准测试 (Benchmark)
     * 场景：测试本机 CPU 执行 tryAcquire 的极限速度。
     * 意义：评估引入限流对系统延迟的微小影响。
     */
    @Test
    @DisplayName("性能测试：本机限流器极限吞吐量 (OPS)")
    public void testPerformance() {
        // 创建一个极大容量的限流器，确保不会阻塞，只测逻辑开销
        RateLimiter limiter = RateLimiter.create(5_000_000.0);

        int iterations = 1_000_000; // 一百万次调用
        log.info(">>> 开始性能压测，执行 {} 次 tryAcquire...", iterations);

        // 预热 JVM
        for (int i = 0; i < 10000; i++) limiter.tryAcquire();

        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            limiter.tryAcquire();
        }
        long end = System.nanoTime();

        long durationNs = end - start;
        double durationMs = durationNs / 1_000_000.0;
        double ops = (iterations * 1_000_000_000.0) / durationNs;

        log.info(">>> 压测完成！");
        log.info("总耗时: {} ms", String.format("%.2f", durationMs));
        log.info("单次检查平均耗时: {} ns", String.format("%.2f", (double)durationNs / iterations));
        log.info("本机极限吞吐量 (OPS): {} 万次/秒", String.format("%.2f", ops / 10000));

        // 性能评价输出
        if (ops > 10_000_000) {
            log.info("评价: 性能极佳！限流逻辑对业务几乎无损耗。");
        } else if (ops > 1_000_000) {
            log.info("评价: 性能良好。");
        } else {
            log.warn("评价: 性能一般，请注意高并发下的 CPU 开销。");
        }
    }
}