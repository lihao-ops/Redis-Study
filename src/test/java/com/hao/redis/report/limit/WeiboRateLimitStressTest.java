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
 * 微博发布接口分布式限流压测报告
 *
 * 测试目的：
 * 验证在 WeiboController 上添加 @SimpleRateLimit 后，分布式限流是否在真实 HTTP 请求链路中生效。
 *
 * 前置条件：
 * 请确保 WeiboController.createPost 方法已添加 @SimpleRateLimit(qps = "10", type = DISTRIBUTED)
 */
@Slf4j
@SpringBootTest
@AutoConfigureMockMvc
public class WeiboRateLimitStressTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("微博发布接口_分布式限流压测_持续1秒_验证QPS阈值")
    public void testWeiboPublishRateLimit() throws InterruptedException {
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

        log.info("========== 开始压测: 持续 {} ms, 目标 QPS=10 ==========", duration);

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
                            
                            // 稍微休眠 10ms，模拟真实流量间隔，防止本地 MockMvc 线程阻塞导致压测时间偏差太大
                            Thread.sleep(10);
                        } catch (Exception e) {
                            log.error("请求异常", e);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        log.info("========== 压测报告 ==========");
        log.info("总请求数: {}", totalRequests.get());
        log.info("成功放行: {}", successCount.get());
        log.info("限流拦截: {}", limitCount.get());
        log.info("============================");

        // 验证逻辑：
        // 1秒内 QPS=10。考虑到 Guava 预热、系统执行误差，成功数应在 8 ~ 15 之间。
        // 如果是 1，说明时间太短；如果是 100，说明限流失效。
        assertTrue(successCount.get() >= 8 && successCount.get() <= 15,
                "1秒内成功请求数应接近 QPS 阈值 (10)，实际成功数: " + successCount.get());
    }
}