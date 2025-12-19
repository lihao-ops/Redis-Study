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

/**
 * Redis 集群性能压测
 *
 * 类职责：
 * 验证 Redis 集群在并发场景下的写入与读取性能。
 *
 * 测试目的：
 * 1. 验证高并发写入与读取的吞吐能力与稳定性。
 * 2. 评估连接池与路由是否存在瓶颈。
 *
 * 设计思路：
 * - 使用线程池与倒计时门闩模拟瞬时并发。
 * - 生成独立批次前缀确保测试隔离。
 * - 统计成功/失败与TPS指标。
 *
 * 为什么需要该类：
 * 集群性能是系统上限关键指标，需通过可重复的压测验证。
 *
 * 核心实现思路：
 * - 分阶段执行写入与读取压测。
 * - 输出性能指标并清理测试数据。
 */
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
     * Redis 集群高并发性能基准测试
     *
     * 实现逻辑：
     * 1. 生成批次前缀并创建并发写入任务。
     * 2. 并发读取并统计成功率与TPS。
     * 3. 输出报告并清理测试数据。
     *
     * @throws InterruptedException 线程中断异常
     */
    @Test
    public void testClusterHighConcurrencyPerformance() throws InterruptedException {
        // 实现思路：
        // 1. 先写入后读取，分阶段统计性能指标。
        // 1. 数据准备
        String batchId = UUID.randomUUID().toString().substring(0, 8);
        String keyPrefix = "bench:" + batchId + ":";
        String valuePayload = "data-" + UUID.randomUUID(); // 模拟固定大小的数据载荷

        // 使用线程安全的列表记录键，用于后续清理
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

        log.info("Redis集群压测启动|Redis_cluster_benchmark_start");
        log.info("批次ID|Batch_id,id={}", batchId);
        log.info("压测配置|Benchmark_config,threads={},requestsPerThread={},totalRequests={}",
                THREAD_COUNT, REQUESTS_PER_THREAD, TOTAL_REQUESTS);

        StopWatch stopWatch = new StopWatch("Redis Cluster Benchmark");

        try {
            // ==================== 2. 并发写入测试 ====================
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
                                log.error("写入失败|Write_fail,key={},reason={}", key, e.getMessage());
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        writeFinishLatch.countDown();
                    }
                });
            }

            log.info("阶段1_并发写入|Phase1_concurrent_write_start");
            stopWatch.start("Write Phase");
            long writeStartTime = System.currentTimeMillis();

            startLatch.countDown(); // 啪！发令，所有线程同时开始写入
            writeFinishLatch.await(); // 等待所有线程写入完成

            long writeEndTime = System.currentTimeMillis();
            stopWatch.stop();

            long writeDuration = writeEndTime - writeStartTime;
            double writeTps = (writeSuccess.get() / (double) writeDuration) * 1000;

            // ==================== 3. 并发读取测试 ====================
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

            log.info("阶段2_并发读取|Phase2_concurrent_read_start");
            stopWatch.start("Read Phase");
            long readStartTime = System.currentTimeMillis();

            readStartLatch.countDown(); // 啪！发令
            readFinishLatch.await(); // 等待所有线程读取完成

            long readEndTime = System.currentTimeMillis();
            stopWatch.stop();

            long readDuration = readEndTime - readStartTime;
            double readTps = (readSuccess.get() / (double) readDuration) * 1000;

            // ==================== 4. 生成压测报告 ====================
            log.info("压测报告_写入|Write_report,costMs={},success={},fail={},tps={},avgLatencyMs={}",
                    writeDuration, writeSuccess.get(), writeFail.get(),
                    String.format("%.2f", writeTps), String.format("%.3f", (double) writeDuration / TOTAL_REQUESTS));
            log.info("压测报告_读取|Read_report,costMs={},success={},fail={},tps={},avgLatencyMs={}",
                    readDuration, readSuccess.get(), readFail.get(),
                    String.format("%.2f", readTps), String.format("%.3f", (double) readDuration / TOTAL_REQUESTS));

        } finally {
            // ==================== 5. 资源清理 ====================
            log.info("阶段3_清理数据|Phase3_cleanup_start,count={}", generatedKeys.size());
            // 使用单线程清理即可，避免占用过多资源，且不计入压测时间
            for (String key : generatedKeys) {
                try {
                    redisClient.del(key);
                } catch (Exception e) {
                    // 忽略清理时的异常
                }
            }
            log.info("数据清理完成|Cleanup_done");
            executor.shutdown();
        }
    }
}
