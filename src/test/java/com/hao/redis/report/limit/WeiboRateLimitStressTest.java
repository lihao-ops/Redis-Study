package com.hao.redis.report.limit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hao.redis.dal.model.WeiboPost;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 微博发布接口分布式限流压测
 *
 * 类职责：
 * 验证发布接口在真实 HTTP 链路中的分布式限流效果。
 *
 * 测试目的：
 * 1. 验证发布接口在限流阈值下的放行能力。
 * 2. 验证限流触发时的响应是否符合预期。
 *
 * 设计思路：
 * - 使用 MockMvc 并发发起请求。
 * - 统计成功与限流数量并进行断言。
 *
 * 为什么需要该类：
 * 发布接口是写入热点，必须验证限流阈值的实际效果。
 *
 * 核心实现思路：
 * - 在固定时间窗口内持续发压。
 * - 对结果进行汇总并校验范围。
 */
@Slf4j
@SpringBootTest
@AutoConfigureMockMvc
public class WeiboRateLimitStressTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 微博发布接口分布式限流压测
     *
     * 实现逻辑：
     * 1. 在固定时间窗口内并发发起请求。
     * 2. 统计成功与限流数量并校验范围。
     *
     * @throws InterruptedException 线程中断异常
     */
    @Test
    @DisplayName("微博发布接口_分布式限流压测_持续1秒_验证QPS阈值")
    public void testWeiboPublishRateLimit() throws InterruptedException {
        // 实现思路：
        // 1. 固定时间窗口发压并统计结果。
        // 模拟并发线程数
        int threadCount = 20;
        // 持续压测时间 (毫秒)
        long duration = 1000;
        long endTime = System.currentTimeMillis() + duration;

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger limitCount = new AtomicInteger(0);
        AtomicInteger totalRequests = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        log.info("开始压测|Stress_start,durationMs={},targetQps=10", duration);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    // 每个线程在规定时间内不断发起请求
                    while (System.currentTimeMillis() < endTime) {
                        totalRequests.incrementAndGet();
                        WeiboPost post = new WeiboPost();
                        post.setContent("Stress Test Post");

                        try {
                            int status = mockMvc.perform(post("/weibo/weibo")
                                            .header("userId", "1001")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(post)))
                                    .andReturn().getResponse().getStatus();

                            if (status == 200) successCount.incrementAndGet();
                            else if (status == 429) limitCount.incrementAndGet();
                            
                            // 稍微休眠10毫秒，模拟真实流量间隔，避免本地线程阻塞导致压测偏差
                            Thread.sleep(10);
                        } catch (Exception e) {
                            log.error("请求异常|Request_exception", e);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        log.info("压测报告|Stress_report");
        log.info("总请求数|Total_requests,count={}", totalRequests.get());
        log.info("成功放行|Success_count,count={}", successCount.get());
        log.info("限流拦截|Limited_count,count={}", limitCount.get());

        // 验证逻辑：
        // 1秒内阈值为10，考虑预热与执行误差，成功数应在8~15之间。
        // 如果是 1，说明时间太短；如果是 100，说明限流失效。
        assertTrue(successCount.get() >= 8 && successCount.get() <= 15,
                "1秒内成功请求数应接近 QPS 阈值 (10)，实际成功数: " + successCount.get());
    }
}
