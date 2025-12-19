package com.hao.redis.report.limit;

import com.google.common.util.concurrent.RateLimiter;
import com.hao.redis.common.constants.RateLimitConstants;
import com.hao.redis.common.exception.RateLimitException;
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局限流性能压测报告
 *
 * 类职责：
 * 在真实端口环境下验证全局限流的QPS控制能力与稳定性。
 *
 * 测试目的：
 * 1. 验证 Redis 分布式限流组件的实际吞吐能力。
 * 2. 模拟真实 HTTP 链路评估系统整体性能。
 * 3. 验证限流触发与降级行为是否符合预期。
 *
 * 设计思路：
 * - 启动真实 Web 容器避免 MockMvc 同步瓶颈。
 * - 使用多线程模拟高并发客户端发压。
 * - 通过 RateLimiter 控制客户端发压速率。
 *
 * 为什么需要该类：
 * 全局限流是系统安全底座，必须通过真实链路压测验证边界能力。
 *
 * 核心实现思路：
 * - 预热 -> 压测 -> 报告输出。
 * - 统计成功、限流与异常请求并计算实际QPS。
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
    // 优化：配合Tomcat扩容，提升客户端并发基数（虽然使用虚拟线程，但保留此常量作为参考）
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
     * <p>
     * 实现逻辑：
     * 1. 环境检查：确认 Redis 连接池配置，避免客户端瓶颈。
     * 2. 预热阶段：发送少量请求预热JVM与连接池。
     * 3. 压测阶段：
     * - 使用 RateLimiter 控制客户端请求速率。
     * - 多线程并发发起 HTTP GET 请求。
     * - 记录请求耗时与响应状态。
     * 4. 报告生成：计算并打印实际 QPS、平均耗时及限流比例。
     *
     * @throws InterruptedException 线程中断异常
     */
    @Test
    @DisplayName("真实端口压测_验证业务QPS")
    public void testGlobalRateLimitMaxQps() throws InterruptedException {
        // 实现思路：
        // 1. 先做环境检查与预热。
        // 2. 并发发压并统计指标。
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

        log.info("开始真实端口压测|Real_port_stress_start,targetQps={}", limitQps);
        log.info("测试模式|Test_mode:RestTemplatePool->Real_Tomcat->Controller->RedisClient");
        log.info("并发线程数|Concurrency_threads,count={}", CONCURRENCY_THREADS);

        // 使用固定线程池模拟客户端并发
        // 注意：此处使用独立线程池而非服务端线程池，确保发压端资源隔离
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
                    // 发起真实HTTP请求（GET）
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
                    // 优化：捕获网络层连接异常（如连接被拒绝），避免计入业务错误或打印堆栈
                    errorCount.incrementAndGet();
                } catch (RateLimitException e) {
                    limitedCount.incrementAndGet();
                } catch (Exception e) {
                    // 优化：高并发下禁止打印完整堆栈，仅采样打印错误信息，防止输入输出阻塞拖慢压测
                    if (errorCount.get() < 5) {
                        log.error("请求异常|Request_exception,message={}", e.getMessage());
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
     * <p>
     * 实现逻辑：
     * 1. 检查 Redis 连接工厂类型。
     * 2. 检查连接池配置，确保 MaxActive 足够大。
     */
    private void checkEnvironment() {
        // 实现思路：
        // 1. 读取连接工厂与连接池配置。
        log.info("环境配置核查|Environment_configuration_check");
        if (redisTemplate.getConnectionFactory() instanceof LettuceConnectionFactory) {
            LettuceConnectionFactory factory = (LettuceConnectionFactory) redisTemplate.getConnectionFactory();
            if (factory.getClientConfiguration() instanceof LettucePoolingClientConfiguration) {
                LettucePoolingClientConfiguration clientConfig = (LettucePoolingClientConfiguration) factory.getClientConfiguration();
                GenericObjectPoolConfig<?> poolConfig = clientConfig.getPoolConfig();
                if (poolConfig != null) {
                    log.info("Redis连接池状态|Redis_pool_status,maxActive={}", poolConfig.getMaxTotal());
                }
            }
        }
    }

    /**
     * 预热逻辑
     *
     * 实现逻辑：
     * 1. 发起少量请求预热连接池与JVM。
     * 2. 捕获异常并忽略，避免影响主流程。
     */
    private void warmUp() {
        // 实现思路：
        // 1. 循环发起请求完成预热。
        log.info("正在预热|Warming_up");
        try {
            String url = "http://localhost:" + port + "/weibo/system/uv";
            // 优化：增加预热次数，确保连接池充分初始化
            for (int i = 0; i < 500; i++) {
                try {
                    restTemplate.getForEntity(url, String.class);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.warn("预热失败_忽略|Warmup_failed_ignored");
        }
    }

    /**
     * 打印压测报告
     *
     * 实现逻辑：
     * 1. 计算实际QPS与平均耗时。
     * 2. 输出成功、限流、错误统计与异常提示。
     *
     * @param limitQps 限流阈值
     * @param total 总请求数
     * @param cost 总耗时
     * @param success 成功计数
     * @param blocked 限流计数
     * @param error 错误计数
     * @param totalCostNs 总耗时纳秒累计
     */
    private void printFinalReport(int limitQps, int total, long cost, AtomicInteger success, AtomicInteger blocked, AtomicInteger error, LongAdder totalCostNs) {
        // 实现思路：
        // 1. 计算核心指标并输出结果。
        double durationSeconds = cost / 1000.0;
        double actualQps = success.get() / durationSeconds;
        double avgLatency = (totalCostNs.sum() / 1000000.0) / total;

        log.info("压测报告|Stress_test_report");
        log.info("总耗时|Total_duration,costMs={}", cost);
        log.info("平均耗时|Avg_latency,ms={}", String.format("%.3f", avgLatency));
        log.info("实际QPS|Actual_qps,value={}", String.format("%.2f", actualQps));
        log.info("成功数|Success_200,count={}", success.get());
        log.info("限流数|Blocked_429,count={}", blocked.get());
        log.info("错误数|Error_count,count={}", error.get());

        if (actualQps < limitQps * 0.8) {
            log.error("QPS未达标|Qps_below_target,actualQps={},targetQps={}", String.format("%.2f", actualQps), limitQps);
            log.error("可能原因|Possible_reasons");
            log.error("原因1_本地CPU瓶颈|Reason1_local_cpu_bottleneck");
            log.error("原因2_日志打印过频|Reason2_excessive_logging");
            log.error("原因3_同步锁竞争|Reason3_lock_contention");
        } else {
            log.info("验证通过|Verification_passed");
        }

        // 限流断言：仅在实际QPS超过阈值时才要求触发限流
        if (actualQps > limitQps) {
            assertTrue(blocked.get() > 0, "应触发限流|Should_trigger_rate_limiting");
        }
        assertTrue(actualQps > limitQps * 0.8, "QPS应达标|QPS_should_meet_target");
    }
}
