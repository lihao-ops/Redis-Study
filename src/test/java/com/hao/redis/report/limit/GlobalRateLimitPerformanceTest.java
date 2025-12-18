package com.hao.redis.report.limit;

import com.hao.redis.common.constants.RateLimitConstants;
import com.hao.redis.common.exception.RateLimitException;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.NestedServletException;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 全局限流性能压测报告
 *
 * 类职责：
 * 验证系统在 "单机限流 + 分布式限流" 双重防护下的极限承载能力。
 *
 * 测试目的：
 * 1. 验证全局限流阈值（GLOBAL_SERVICE_QPS）是否生效。
 * 2. 验证高并发下限流组件（Guava + Redis）的稳定性与性能损耗。
 * 3. 产出实际可通行的 QPS 数据，作为容量规划依据。
 *
 * 设计思路：
 * - 复用生产环境线程池 ThreadPoolTaskExecutor 模拟真实并发。
 * - 使用 CountDownLatch 确保并发压力同时释放。
 * - 统计成功数与拦截数，计算实际 QPS 并与预期值比对。
 */
@Slf4j
@SpringBootTest
@AutoConfigureMockMvc
public class GlobalRateLimitPerformanceTest {

    // 压测参数
    private static final int LOAD_FACTOR = 20; // 负载因子：模拟20倍于阈值的流量，确保触发限流并持续施压
    private static final double QPS_TOLERANCE = 1.2; // 容忍度：允许实际QPS有20%的突发上浮，以应对令牌桶的预消费和突发能力

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("virtualThreadExecutor")
    private ExecutorService stressExecutor;

    /**
     * 全局限流压测_双重防护_验证QPS上限
     *
     * 实现逻辑：
     * 1. 读取全局限流配置阈值。
     * 2. 构造 2 倍于阈值的请求量，确保触发限流。
     * 3. 多线程并发发起 MockMVC 请求。
     * 4. 统计 HTTP 200 (通过) 与 HTTP 429 (拦截) 的数量。
     * 5. 计算耗时与实际 QPS，断言结果在允许误差范围内。
     */
    @Test
    @DisplayName("全局限流压测_双重防护_验证QPS上限")
    public void testGlobalRateLimitMaxQps() throws InterruptedException {
        // 实现思路：
        // 1. 基于全局QPS阈值，构造2倍的并发请求，确保能稳定触发限流。
        // 2. 使用CountDownLatch保证所有线程同时开始执行，模拟瞬时高并发。
        // 3. 捕获MockMvc执行结果，区分成功(200)和限流(429/RateLimitException)，并分别计数。
        // 4. 测试结束后，计算实际平均QPS，并断言其不超过 "阈值 * 容忍度"，以验证限流效果。

        // 步骤1：获取全局限流阈值
        int limitQps = Integer.parseInt(RateLimitConstants.GLOBAL_SERVICE_QPS);
        // 步骤2：模拟超量请求，确保触发限流
        int requestCount = limitQps * LOAD_FACTOR;
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger limitedCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(requestCount);

        log.info("开始全局限流压测|Start_global_rate_limit_stress_test,plan_requests={},expected_qps={}", requestCount, limitQps);
        long start = System.currentTimeMillis();

        for (int i = 0; i < requestCount; i++) {
            // 使用专用压测线程池执行并发请求
            stressExecutor.execute(() -> {
                try {
                    // 访问一个轻量级接口 (UV统计)，减少业务逻辑干扰，纯测限流组件性能
                    int status = mockMvc.perform(get("/weibo/system/uv"))
                            .andReturn().getResponse().getStatus();

                    if (status == 200) {
                        successCount.incrementAndGet();
                    } else if (status == 429) {
                        limitedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // MockMvc 捕获的异常处理逻辑：
                    // 由于限流逻辑在 Interceptor 中，抛出的 RateLimitException 会被 Spring MVC 包装成 NestedServletException。
                    // 因此，需要解开异常的包装，获取真正的根异常（cause）。
                    // 如果根异常是 RateLimitException，说明是预期的限流行为，计入限流统计。
                    Throwable cause = e;
                    if (e instanceof NestedServletException) {
                        cause = e.getCause();
                    }
                    if (cause instanceof RateLimitException) {
                        limitedCount.incrementAndGet();
                    } else {
                        log.error("非预期的请求执行异常|Unexpected_request_execution_exception", e);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // 等待所有任务完成
        latch.await();
        long end = System.currentTimeMillis();
        long cost = end - start;

        // 计算实际通过的 QPS 与 施压端生成速率
        double actualQps = 0;
        double loadQps = 0;
        if (cost > 0) {
            actualQps = successCount.get() / (cost / 1000.0);
            loadQps = requestCount / (cost / 1000.0);
        }

        log.info("压测报告汇总|Stress_test_report_summary");
        log.info("配置阈值|Configured_threshold_qps,value={}", limitQps);
        log.info("压测耗时|Test_duration_ms,cost={}", cost);
        log.info("请求生成速率|Request_generation_rate_qps,value={}", String.format(Locale.ROOT, "%.2f", loadQps));
        log.info("总请求数|Total_requests,count={}", requestCount);
        log.info("成功放行|Success_passed,count={}", successCount.get());
        log.info("限流拦截|Rate_limited,count={}", limitedCount.get());
        log.info("实际通过QPS|Actual_passed_qps,value={}", String.format(Locale.ROOT, "%.2f", actualQps));

        // 验证逻辑：
        // 现象解释：
        // 即使 loadQps (如 526) < limitQps (1000)，仍可能出现 limitedCount > 0。
        // 原因：CountDownLatch 导致瞬时并发极高（微突发流量），瞬间速率远超 1000，触发令牌桶限流。
        // 这证明了限流器对突发流量的敏感性，符合预期。
        //
        // 性能瓶颈分析 (关键)：
        // 若 actualQps (330) 远低于 limitQps (1000)，且 limitedCount 极大。
        // 根本原因通常是 "日志洪水"：服务端对每次拦截都打印 WARN 日志。
        // 控制台日志 (ConsoleAppender) 通常是同步且加锁的，海量日志打印导致所有线程在 System.out 锁上排队。
        // 结论：当前的 330 QPS 是被日志 IO 拖累后的结果。生产环境应开启 "限流日志采样" 或 "异步日志"。
        // 1. 必须触发限流 (因为请求量是阈值的2倍)
        assertTrue(limitedCount.get() > 0, "高并发下应触发限流保护");
        
        // 2. 验证实际QPS是否在合理范围内
        // 注意：MockMvc压测的QPS通常受限于测试执行本身的开销（Load QPS），可能远低于服务端真实极限。
        // 这里的断言主要防止限流失效导致QPS暴涨，而非验证性能下限。
        assertTrue(actualQps <= limitQps * QPS_TOLERANCE, "实际平均QPS不应显著超过设定的阈值");
    }
}