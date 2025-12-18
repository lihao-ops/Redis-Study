package com.hao.redis.common.aspect;

import com.hao.redis.common.exception.RateLimitException;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 单机限流切面测试类
 *
 * 测试目的：
 * 1. 验证 @SimpleRateLimit 注解在 Controller 层（接口级别）是否生效。
 * 2. 验证 @SimpleRateLimit 注解在 Service 层（服务级别）是否生效。
 * 3. 模拟高并发场景，验证令牌桶算法的限流准确性。
 *
 * 设计思路：
 * - 使用内部类定义测试用的 Controller 和 Service，避免污染业务代码。
 * - 使用 MockMvc 模拟 HTTP 请求测试接口限流。
 * - 使用多线程 ExecutorService 模拟并发测试服务限流。
 */
@Slf4j
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "test.rate.limit=5") // 模拟配置文件中的限流阈值
public class SimpleRateLimitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimitTestService testService;

    /**
     * 测试场景1：接口级别限流（Controller）
     * 预期：QPS=1，连续请求5次，应该只有1次成功（HTTP 200），其余4次被限流（HTTP 429）。
     */
    @Test
    @DisplayName("接口级限流_QPS为1_连续请求应触发限流")
    public void testInterfaceRateLimit() throws Exception {
        int successCount = 0;
        int limitCount = 0;

        // 模拟短时间内发起 5 次请求
        for (int i = 0; i < 5; i++) {
            try {
                // 使用 MockMvc 发起请求，获取响应状态码
                int status = mockMvc.perform(get("/test/rate-limit/interface"))
                        .andReturn().getResponse().getStatus();
                
                if (status == 200) successCount++;
                if (status == 429) limitCount++;
                
            } catch (Exception e) {
                log.error("请求异常|Request_exception", e);
            }
        }

        log.info("接口限流测试结果|Interface_limit_result,success={},limited={}", successCount, limitCount);
        
        // 断言：至少有一次成功（令牌桶初始可能有令牌），且肯定有被限流的请求
        assertTrue(successCount >= 1, "至少应有一次成功");
        assertTrue(limitCount > 0, "高频请求应触发限流");
    }

    /**
     * 测试场景2：服务级别限流（Service）+ 配置文件属性读取
     * 预期：QPS配置为5，并发10个线程调用，应有部分失败。
     */
    @Test
    @DisplayName("服务级限流_并发调用_验证令牌桶保护")
    public void testServiceRateLimit() throws InterruptedException {
        // 模拟 Web 上下文，否则 Aspect 中的 getRequestUri() 会返回 "unknown"
        // 在 Service 层测试中，通常需要 Mock RequestContext
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/test/service/method");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCounter = new AtomicInteger(0);
        AtomicInteger exceptionCounter = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    testService.doSomething();
                    successCounter.incrementAndGet();
                } catch (RateLimitException e) {
                    exceptionCounter.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(); // 等待所有线程完成
        executor.shutdown();

        log.info("服务限流测试结果|Service_limit_result,success={},limited={}", successCounter.get(), exceptionCounter.get());

        // 断言：配置了 QPS=5，并发 10 个，理论上成功数接近 5（受令牌桶预热影响可能略有波动），但绝不应全部成功
        assertTrue(exceptionCounter.get() > 0, "并发场景下应触发限流保护");
        
        // 清理上下文
        RequestContextHolder.resetRequestAttributes();
    }

    // ================= 内部测试辅助类 =================

    @TestConfiguration
    static class TestConfig {
        @Bean
        public RateLimitTestController rateLimitTestController() {
            return new RateLimitTestController();
        }

        @Bean
        public RateLimitTestService rateLimitTestService() {
            return new RateLimitTestService();
        }
    }

    @RestController
    static class RateLimitTestController {
        // QPS = 1，非常严格的限流
        @GetMapping("/test/rate-limit/interface")
        @SimpleRateLimit(qps = "1")
        public String test() {
            return "success";
        }
    }

    static class RateLimitTestService {
        // 使用配置文件中的属性 test.rate.limit = 5
        @SimpleRateLimit(qps = "${test.rate.limit}")
        public void doSomething() {
            // 模拟业务逻辑
        }
    }
}