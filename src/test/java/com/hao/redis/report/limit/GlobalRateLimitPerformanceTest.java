package com.hao.redis.report.limit;

import com.google.common.util.concurrent.RateLimiter;
import com.hao.redis.common.constants.RateLimitConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局限流性能压测报告 (真实环境版)
 *
 * 设计目的：
 * 1. 验证高并发场景下 Redis 分布式限流组件的实际 QPS 控制能力。
 * 2. 模拟真实 HTTP 请求链路（Tomcat -> Controller -> Redis），评估系统整体吞吐量。
 * 3. 验证限流触发时的降级行为（HTTP 429）是否符合预期。
 *
 * 实现思路：
 * - 启动真实 Web 容器（RANDOM_PORT），避免 MockMvc 的线程同步瓶颈。
 * - 使用多线程模拟高并发客户端（Client），发起真实网络请求。
 * - 采用 Guava RateLimiter 控制客户端发压速率，验证服务端限流阈值。
 * - 统计成功（200）、限流（429）及异常请求数，计算实际 QPS。
 */
@Slf4j
// 优化：
// 1. server.tomcat.threads.max=800: 扩容 Tomcat 线程池，打破默认 200 线程的并发瓶颈
// 2. logging.level.root=WARN: 降低无关日志噪音；保留当前压测类日志为 INFO
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "server.tomcat.threads.max=800",
        "logging.level.root=WARN",
        "logging.level.com.hao.redis.report.limit=INFO"
    }
)
public class GlobalRateLimitPerformanceTest {

    private static final int LOAD_FACTOR = 30;
    // 优化：配合 Tomcat 扩容，提升客户端并发基数 (虽然使用虚拟线程，但保留此常量作为参考)
    private static final int CONCURRENCY_THREADS = 800;

    // 注入 RestTemplate，需确保配置了连接池以支持高并发
    @Autowired
    private RestTemplate restTemplate;

    @LocalServerPort
    private int port;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private ExecutorService stressExecutor;

    /**
     * 真实端口压测_验证业务QPS
     *
     * 实现逻辑：
     * 1. 环境检查：确认 Redis 连接池配置，避免客户端瓶颈。
     * 2. 预热阶段：发送少量请求预热 JVM JIT 编译与连接池。
     * 3. 压测阶段：
     *    - 使用 RateLimiter 控制客户端请求速率。
     *    - 多线程并发发起 HTTP GET 请求。
     *    - 记录请求耗时与响应状态。
     * 4. 报告生成：计算并打印实际 QPS、平均耗时及限流比例。
     *
     * @throws InterruptedException 线程中断异常
     */
    @Test
    @DisplayName("真实端口压测_验证业务QPS")
    public void testGlobalRateLimitMaxQps() throws InterruptedException {
        // --- 0. 环境诊断 ---
        checkEnvironment();

        // --- 1. 准备压测参数 ---
        int limitQps = Integer.parseInt(RateLimitConstants.GLOBAL_SERVICE_QPS);
        int requestCount = limitQps * LOAD_FACTOR;
        // 客户端发压速率设置为限流阈值的 3 倍，确保能触发限流
        double clientLoadQps = limitQps * 3.0;
        RateLimiter trafficShaper = RateLimiter.create(clientLoadQps);

        // 计数器初始化
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger limitedCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        LongAdder totalRequestCostNs = new LongAdder();
        CountDownLatch latch = new CountDownLatch(requestCount);

        log.info(">>>>>> 开始真实端口压测|Start_real_port_stress_test,target_QPS={}", limitQps);
        log.info("测试模式|Test_mode: RestTemplate(pool) -> Real_Tomcat -> Controller -> RedisClient");
        log.info("并发线程|Concurrency_threads: {}", CONCURRENCY_THREADS);

        // 使用固定线程池模拟客户端并发
        // 注意：此处使用独立线程池而非 Server 端线程池，确保发压端资源隔离
        stressExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // --- 2. 预热 (可选，防止冷启动影响) ---
        warmUp();

        long start = System.currentTimeMillis();

        // --- 3. 执行压测 ---
        for (int i = 0; i < requestCount; i++) {
            // 令牌桶控制发压速率
            trafficShaper.acquire();
            stressExecutor.execute(() -> {
                long reqStart = System.nanoTime();
                try {
                    // 发起真实 HTTP GET 请求
                    String url = "http://localhost:" + port + "/weibo/system/uv";
                    ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

                    if (response.getStatusCode() == HttpStatus.OK) {
                        successCount.incrementAndGet();
                    }
                } catch (HttpClientErrorException e) {
                    // 捕获 429 限流异常
                    if (e.getStatusCode().value() == 429) {
                        limitedCount.incrementAndGet();
                    } else {
                        errorCount.incrementAndGet();
                    }
                } catch (ResourceAccessException e) {
                    // 优化：捕获网络层连接异常 (如 Connection refused)，避免计入业务错误或打印堆栈
                    errorCount.incrementAndGet();
                } catch (Exception e) {
                    // 优化：高并发下禁止打印完整堆栈，仅采样打印错误信息，防止 IO 阻塞拖慢压测
                    if (errorCount.get() < 5) {
                        log.error("请求异常|Request_exception: {}", e.getMessage());
                    }
                    errorCount.incrementAndGet();
                } finally {
                    totalRequestCostNs.add(System.nanoTime() - reqStart);
                    latch.countDown();
                }
            });
        }

        latch.await();
        stressExecutor.shutdownNow();

        long cost = System.currentTimeMillis() - start;
        printFinalReport(limitQps, requestCount, cost, successCount, limitedCount, errorCount, totalRequestCostNs);
    }

    /**
     * 环境配置检查
     *
     * 实现逻辑：
     * 1. 检查 Redis 连接工厂类型。
     * 2. 检查连接池配置，确保 MaxActive 足够大。
     */
    private void checkEnvironment() {
        log.info(">>>>>> 环境配置核查|Environment_configuration_check <<<<<<");
        if (redisTemplate.getConnectionFactory() instanceof LettuceConnectionFactory) {
            LettuceConnectionFactory factory = (LettuceConnectionFactory) redisTemplate.getConnectionFactory();
            if (factory.getClientConfiguration() instanceof LettucePoolingClientConfiguration) {
                LettucePoolingClientConfiguration clientConfig = (LettucePoolingClientConfiguration) factory.getClientConfiguration();
                GenericObjectPoolConfig<?> poolConfig = clientConfig.getPoolConfig();
                if (poolConfig != null) {
                    log.info("Redis连接池状态|Redis_pool_status,MaxActive={}", poolConfig.getMaxTotal());
                }
            }
        }
    }

    /**
     * 简单的预热逻辑
     */
    private void warmUp() {
        log.info("正在预热|Warming_up...");
        try {
            String url = "http://localhost:" + port + "/weibo/system/uv";
            // 优化：增加预热次数，确保连接池充分初始化
            for (int i = 0; i < 500; i++) {
                try {
                    restTemplate.getForEntity(url, String.class);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.warn("预热失败_忽略|Warmup_failed_ignored");
        }
    }

    private void printFinalReport(int limitQps, int total, long cost, AtomicInteger success, AtomicInteger blocked, AtomicInteger error, LongAdder totalCostNs) {
        double durationSeconds = cost / 1000.0;
        double actualQps = success.get() / durationSeconds;
        double avgLatency = (totalCostNs.sum() / 1000000.0) / total;

        log.info(">>>>>> 真实业务压测报告|Real_business_stress_test_report <<<<<<");
        log.info("总耗时|Total_duration: {} ms", cost);
        log.info("平均耗时|Avg_latency: {} ms", String.format("%.3f", avgLatency));
        log.info("实际QPS|Actual_QPS: {}", String.format("%.2f", actualQps));
        log.info("--------------------------------------------------");
        log.info("成功数|Success_200: {}", success.get());
        log.info("限流数|Blocked_429: {}", blocked.get());
        log.info("错误数|Error_count: {}", error.get());

        if (actualQps < limitQps * 0.8) {
            log.error("❌ QPS未达标|QPS_below_target,actual={},target={}", String.format("%.2f", actualQps), limitQps);
            log.error("可能原因|Possible_reasons:");
            log.error("1. 本地CPU瓶颈|Local_CPU_bottleneck");
            log.error("2. 日志打印过频|Excessive_logging");
            log.error("3. 同步锁竞争|Synchronized_lock_contention");
        } else {
            log.info("✅ 验证通过|Verification_passed");
        }

        assertTrue(blocked.get() > 0, "应触发限流|Should_trigger_rate_limiting");
        assertTrue(actualQps > limitQps * 0.8, "QPS应达标|QPS_should_meet_target");
    }
}
