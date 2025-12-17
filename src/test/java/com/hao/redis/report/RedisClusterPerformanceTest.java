package com.hao.redis.report;

import com.hao.redis.integration.redis.RedisClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.StopWatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@SpringBootTest
public class RedisClusterPerformanceTest {

    @Autowired
    private RedisClient<String> redisClient;

    // 压测参数配置
    private static final int THREAD_COUNT = 100;           // 并发线程数 (模拟 20 个并发用户)
    private static final int REQUESTS_PER_THREAD = 500;   // 每个用户执行 500 次操作
    private static final int TOTAL_REQUESTS = THREAD_COUNT * REQUESTS_PER_THREAD; // 总请求量 10,000

    /**
     * Redis 集群高并发性能基准测试 (Benchmark)
     * <p>
     * 实验目的:
     * 1. 验证 Redis 集群在多线程并发场景下的稳定性与吞吐量 (TPS)。
     * 2. 模拟真实业务流量，检测是否存在连接池瓶颈或路由错误。
     * <p>
     * 亮点设计 (面试加分项):
     * 1. **真实并发**: 使用 ExecutorService + CountDownLatch 模拟“瞬时并发”，而非简单的单线程循环。
     * 2. **数据隔离**: 使用 UUID 生成批次前缀，确保测试互不干扰，且无脏数据残留。
     * 3. **精准度量**: 统计成功/失败数、计算 TPS (QPS) 及平均响应时间。
     */
    @Test
    public void testClusterHighConcurrencyPerformance() throws InterruptedException {
        // 1. 数据准备
        String batchId = UUID.randomUUID().toString().substring(0, 8);
        String keyPrefix = "bench:" + batchId + ":";
        String valuePayload = "data-" + UUID.randomUUID(); // 模拟固定大小的 Payload

        // 使用线程安全的 List 记录 Key，用于后续清理
        List<String> generatedKeys = Collections.synchronizedList(new ArrayList<>(TOTAL_REQUESTS));

        // 线程池与同步工具
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);       // 全局发令枪
        CountDownLatch writeFinishLatch = new CountDownLatch(THREAD_COUNT); // 写入完成信号
        CountDownLatch readFinishLatch = new CountDownLatch(THREAD_COUNT);  // 读取完成信号

        AtomicInteger writeSuccess = new AtomicInteger(0);
        AtomicInteger writeFail = new AtomicInteger(0);
        AtomicInteger readSuccess = new AtomicInteger(0);
        AtomicInteger readFail = new AtomicInteger(0);

        log.info("============================================================");
        log.info(">>> Redis Cluster 高并发性能压测启动 <<<");
        log.info(">>> 批次 ID: {}", batchId);
        log.info(">>> 压测配置: {} 线程 x {} 请求 = {} 总请求", THREAD_COUNT, REQUESTS_PER_THREAD, TOTAL_REQUESTS);
        log.info("============================================================");

        StopWatch stopWatch = new StopWatch("Redis Cluster Benchmark");

        try {
            // ==================== 2. 并发写入测试 (Write Benchmark) ====================
            for (int i = 0; i < THREAD_COUNT; i++) {
                final int threadIdx = i;
                executor.submit(() -> {
                    try {
                        startLatch.await(); // 阻塞，等待主线程发令
                        for (int j = 0; j < REQUESTS_PER_THREAD; j++) {
                            String key = keyPrefix + threadIdx + ":" + j;
                            try {
                                redisClient.set(key, valuePayload);
                                generatedKeys.add(key);
                                writeSuccess.incrementAndGet();
                            } catch (Exception e) {
                                writeFail.incrementAndGet();
                                log.error("写入失败 Key: {}, 原因: {}", key, e.getMessage());
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        writeFinishLatch.countDown();
                    }
                });
            }

            log.info(">>> [Phase 1] 正在进行并发写入测试...");
            stopWatch.start("Write Phase");
            long writeStartTime = System.currentTimeMillis();

            startLatch.countDown(); // 啪！发令，所有线程同时开始写入
            writeFinishLatch.await(); // 等待所有线程写入完成

            long writeEndTime = System.currentTimeMillis();
            stopWatch.stop();

            long writeDuration = writeEndTime - writeStartTime;
            double writeTps = (writeSuccess.get() / (double) writeDuration) * 1000;

            // ==================== 3. 并发读取测试 (Read Benchmark) ====================
            // 重置发令枪
            CountDownLatch readStartLatch = new CountDownLatch(1);

            for (int i = 0; i < THREAD_COUNT; i++) {
                final int threadIdx = i;
                executor.submit(() -> {
                    try {
                        readStartLatch.await(); // 等待发令
                        for (int j = 0; j < REQUESTS_PER_THREAD; j++) {
                            String key = keyPrefix + threadIdx + ":" + j;
                            try {
                                String val = redisClient.get(key);
                                if (valuePayload.equals(val)) {
                                    readSuccess.incrementAndGet();
                                } else {
                                    readFail.incrementAndGet();
                                }
                            } catch (Exception e) {
                                readFail.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        readFinishLatch.countDown();
                    }
                });
            }

            log.info(">>> [Phase 2] 正在进行并发读取测试...");
            stopWatch.start("Read Phase");
            long readStartTime = System.currentTimeMillis();

            readStartLatch.countDown(); // 啪！发令
            readFinishLatch.await(); // 等待所有线程读取完成

            long readEndTime = System.currentTimeMillis();
            stopWatch.stop();

            long readDuration = readEndTime - readStartTime;
            double readTps = (readSuccess.get() / (double) readDuration) * 1000;

            // ==================== 4. 生成权威报告 ====================
            log.info("============================================================");
            log.info(">>> Redis Cluster 压测报告 (Benchmark Report) <<<");
            log.info("------------------------------------------------------------");
            log.info("【写入性能 (Write)】");
            log.info("  - 总耗时    : {} ms", writeDuration);
            log.info("  - 成功/失败 : {} / {}", writeSuccess.get(), writeFail.get());
            log.info("  - TPS (QPS) : {}", String.format("%.2f", writeTps));
            log.info("  - Avg Latency: {} ms", String.format("%.3f", (double) writeDuration / TOTAL_REQUESTS));
            log.info("------------------------------------------------------------");
            log.info("【读取性能 (Read)】");
            log.info("  - 总耗时    : {} ms", readDuration);
            log.info("  - 成功/失败 : {} / {}", readSuccess.get(), readFail.get());
            log.info("  - TPS (QPS) : {}", String.format("%.2f", readTps));
            log.info("  - Avg Latency: {} ms", String.format("%.3f", (double) readDuration / TOTAL_REQUESTS));
            log.info("============================================================");

        } finally {
            // ==================== 5. 资源清理 (Teardown) ====================
            log.info(">>> [Phase 3] 开始清理测试数据 ({} 条)...", generatedKeys.size());
            // 使用单线程清理即可，避免占用过多资源，且不计入压测时间
            for (String key : generatedKeys) {
                try {
                    redisClient.del(key);
                } catch (Exception e) {
                    // 忽略清理时的异常
                }
            }
            log.info(">>> 数据清理完成。");
            executor.shutdown();
        }
    }
}