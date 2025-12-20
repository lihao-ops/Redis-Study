package com.hao.redis.common.interceptor;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 单机限流器内存泄露 JMX 指标验证测试
 *
 * 测试目的：
 * 通过 JVM 标准监控指标（JMX），量化验证内存泄露对系统的实际影响。
 *
 * 核心指标：
 * 1. Heap Memory Used: 堆内存实际使用量（验证是否持续增长）。
 * 2. GC Count/Time: GC 发生的次数与耗时（验证 GC 是否频繁且无效）。
 * 3. Map Size: 实际持有的对象数量（验证是否无法回收）。
 */
@Slf4j
public class SimpleRateLimiterOOMTest {

    // 设定一个危险阈值，模拟生产环境报警线
    private static final int DANGEROUS_THRESHOLD = 50000;

    @Test
    public void testMemoryLeakWithMetrics() throws Exception {
        SimpleRateLimiter simpleRateLimiter = new SimpleRateLimiter();

        // 反射获取 Map 以便统计对象数
        Field limitersField = SimpleRateLimiter.class.getDeclaredField("limiters");
        limitersField.setAccessible(true);
        Map<String, ?> limiters = (Map<String, ?>) limitersField.get(simpleRateLimiter);

        // 获取 JMX Beans (JVM 内部监控接口)
        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

        log.info("开始JMX指标监控测试|Start_JMX_metrics_test");
        printMetrics(memoryMXBean, gcBeans, 0);

        for (int i = 1; i <= 100000; i++) {
            String randomKey = UUID.randomUUID().toString();
            simpleRateLimiter.tryAcquire(randomKey, 1.0);

            // 每 10000 次进行一次深度检查
            if (i % 10000 == 0) {
                // 显式调用 GC，模拟 JVM 试图自救
                // 如果是内存泄露，GC 后内存占用依然很高
                System.gc();
                
                // 打印当前详细指标
                printMetrics(memoryMXBean, gcBeans, limiters.size());

                // 检查是否达到危险阈值
                if (limiters.size() >= DANGEROUS_THRESHOLD) {
                    log.error("❌ 触发内存泄露熔断|Memory_leak_circuit_breaker_triggered");
                    
                    // 收集最终异常指标
                    MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
                    long usedMB = heapUsage.getUsed() / 1024 / 1024;
                    long totalGcTime = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
                    long totalGcCount = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();

                    log.error(">>> 异常指标分析 <<<");
                    log.error("1. [对象堆积] Map大小: {} (预期应 < 1000)", limiters.size());
                    log.error("2. [内存无法释放] 堆内存已用: {} MB (GC后依然居高不下)", usedMB);
                    log.error("3. [GC效率低下] 总GC次数: {}, 总耗时: {} ms (CPU资源浪费)", totalGcCount, totalGcTime);
                    
                    throw new RuntimeException(String.format(
                        "JMX指标异常：内存泄露确认。MapSize=%d, HeapUsed=%dMB, GCTime=%dms", 
                        limiters.size(), usedMB, totalGcTime));
                }
            }
        }
    }

    private void printMetrics(MemoryMXBean memoryBean, List<GarbageCollectorMXBean> gcBeans, int mapSize) {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long usedMB = heapUsage.getUsed() / 1024 / 1024;
        long committedMB = heapUsage.getCommitted() / 1024 / 1024;

        StringBuilder gcInfo = new StringBuilder();
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            // 过滤掉没有运行过的 GC
            if (gcBean.getCollectionCount() > 0) {
                gcInfo.append("[").append(gcBean.getName())
                      .append(": count=").append(gcBean.getCollectionCount())
                      .append(", time=").append(gcBean.getCollectionTime()).append("ms] ");
            }
        }

        log.info("监控快照|Metrics_snapshot, MapSize={}, HeapUsed={}MB, HeapCommitted={}MB, GC_Details={}", 
                mapSize, usedMB, committedMB, gcInfo.toString());
    }
}
