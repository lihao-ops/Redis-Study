package com.hao.redis.common.interceptor;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

/**
 * 单机限流器内存泄露 JMX 指标验证测试 (Caffeine版 - 集成测试)
 *
 * 测试目的：
 * 在 Spring 容器环境下，验证注入的 Caffeine 缓存是否能有效防止内存泄露。
 *
 * 核心指标：
 * 1. Cache Size: 验证缓存大小是否被成功限制在 maximumSize 以内。
 * 2. Heap Memory Used: 验证堆内存使用是否稳定，不会无限增长。
 */
@Slf4j
@SpringBootTest // 启用 Spring Boot 测试容器
public class SimpleRateLimiterOOMTest {

    @Autowired
    private SimpleRateLimiter simpleRateLimiter; // 直接注入被测对象

    @Autowired
    @Qualifier("rateLimiterCache") // 明确注入我们在 CacheConfig 中定义的 Bean
    private Cache<String, ?> limitersCache;

    // 设定一个危险阈值，应与 CacheConfig 中的 maximumSize 保持关联
    private static final int MAXIMUM_SIZE = 50000;
    private static final int DANGEROUS_THRESHOLD = MAXIMUM_SIZE + 1;

    @Test
    public void testMemoryLeakWithMetricsInSpringContext() throws Exception {
        // 获取 JMX Beans (JVM 内部监控接口)
        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

        log.info("开始JMX指标监控测试(Spring集成版)|Start_JMX_metrics_test_with_Spring_context");
        printMetrics(memoryMXBean, gcBeans, limitersCache.estimatedSize());

        for (int i = 1; i <= 100000; i++) {
            String randomKey = UUID.randomUUID().toString();
            simpleRateLimiter.tryAcquire(randomKey, 1.0);

            // 每 10000 次进行一次深度检查
            if (i % 10000 == 0) {
                // 显式调用 GC，模拟 JVM 试图自救
                System.gc();
                
                // Caffeine 的清理是异步的，调用 cleanUp() 有助于触发清理
                limitersCache.cleanUp();
                
                // Caffeine 的 size() 是近似值，用 estimatedSize() 更准确
                long currentSize = limitersCache.estimatedSize();
                
                // 打印当前详细指标
                printMetrics(memoryMXBean, gcBeans, currentSize);

                // 检查是否达到危险阈值
                if (currentSize >= DANGEROUS_THRESHOLD) {
                    log.error("❌ 触发内存泄露熔断|Memory_leak_circuit_breaker_triggered");
                    
                    MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
                    long usedMB = heapUsage.getUsed() / 1024 / 1024;

                    log.error(">>> 异常指标分析 <<<");
                    log.error("1. [对象堆积] Cache大小: {} (预期应 <= {})", currentSize, MAXIMUM_SIZE);
                    
                    throw new RuntimeException(String.format(
                        "JMX指标异常：内存泄露确认。CacheSize=%d, HeapUsed=%dMB", 
                        currentSize, usedMB));
                }
            }
        }
        
        log.info("✅ 测试通过：Cache大小被成功限制在{}以内|Test_passed_cache_size_limited_within_{}", MAXIMUM_SIZE, MAXIMUM_SIZE);
    }

    private void printMetrics(MemoryMXBean memoryBean, List<GarbageCollectorMXBean> gcBeans, long cacheSize) {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long usedMB = heapUsage.getUsed() / 1024 / 1024;
        long committedMB = heapUsage.getCommitted() / 1024 / 1024;

        StringBuilder gcInfo = new StringBuilder();
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            if (gcBean.getCollectionCount() > 0) {
                gcInfo.append("[").append(gcBean.getName())
                      .append(": count=").append(gcBean.getCollectionCount())
                      .append(", time=").append(gcBean.getCollectionTime()).append("ms] ");
            }
        }

        log.info("监控快照|Metrics_snapshot, CacheSize={}, HeapUsed={}MB, HeapCommitted={}MB, GC_Details={}",
                cacheSize, usedMB, committedMB, gcInfo.toString());
    }
}
