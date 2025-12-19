package com.hao.redis.common.aspect;

import com.hao.redis.common.exception.RateLimitException;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
 * 单机限流切面测试
 *
 * 类职责：
 * 验证 @SimpleRateLimit 在控制层与服务层的限流效果。
 *
 * 测试目的：
 * 1. 验证注解在控制层接口的限流是否生效。
 * 2. 验证注解在服务层方法的限流是否生效。
 * 3. 模拟并发场景验证令牌桶限流准确性。
 *
 * 设计思路：
 * - 使用内部类定义测试控制器与服务，避免污染业务代码。
 * - 使用 MockMvc 模拟 HTTP 请求测试接口限流。
 * - 使用多线程执行器模拟并发调用服务限流。
 *
 * 为什么需要该类：
 * 切面是限流核心入口，需要通过测试验证注解生效范围与限流准确性。
 *
 * 核心实现思路：
 * - 覆盖接口级、服务级与分布式限流三类场景。
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

    @Autowired
    private com.hao.redis.common.util.RedisRateLimiter redisRateLimiter;

    /**
     * 接口级别限流验证
     *
     * 实现逻辑：
     * 1. 连续请求多次接口。
     * 2. 统计成功与限流数量并断言。
     *
     * @throws Exception 执行异常
     */
    @Test
    @DisplayName("接口级限流_QPS为1_连续请求应触发限流")
    public void testInterfaceRateLimit() throws Exception {
        // 实现思路：
        // 1. 连续发起请求并统计结果。
        int successCount = 0;
        int limitCount = 0;

        // 模拟短时间内发起 5 次请求
        for (int i = 0; i < 5; i++) {
            // 使用模拟请求发起调用，获取响应状态码
            int status = mockMvc.perform(get("/test/rate-limit/interface"))
                    .andReturn().getResponse().getStatus();

            if (status == 200) {
                successCount++;
            } else if (status == 429) {
                limitCount++;
            }
        }

        log.info("接口限流测试结果|Interface_limit_result,success={},limited={}", successCount, limitCount);

        // 断言：至少有一次成功（令牌桶初始可能有令牌），且肯定有被限流的请求
        assertTrue(successCount >= 1, "至少应有一次成功");
        assertTrue(limitCount > 0, "高频请求应触发限流");
    }

    /**
     * 服务级别限流验证
     *
     * 实现逻辑：
     * 1. 构造请求上下文并并发调用服务方法。
     * 2. 统计成功与限流数量并断言。
     *
     * @throws InterruptedException 线程中断异常
     */
    @Test
    @DisplayName("服务级限流_并发调用_验证令牌桶保护")
    public void testServiceRateLimit() throws InterruptedException {
        // 实现思路：
        // 1. 构造上下文并发调用服务方法。
        // 模拟请求上下文，否则切面获取请求路径会返回默认值
        // 在服务层测试中需要模拟请求上下文
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

        // 断言：阈值为5且并发10次时，成功数应接近5且不应全部成功
        assertTrue(exceptionCounter.get() > 0, "并发场景下应触发限流保护");

        // 清理上下文
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * 分布式限流验证
     *
     * 实现逻辑：
     * 1. 构造请求上下文并循环调用服务方法。
     * 2. 统计成功与限流数量并断言。
     */
    @Test
    @DisplayName("分布式限流_真实Redis集成_验证限流阈值")
    public void testDistributedRateLimit() {
        // 实现思路：
        // 1. 构造上下文并循环触发分布式限流。
        // 1. 准备上下文，确保限流键唯一，避免与其他测试冲突
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/test/distributed/limit/real");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        int successCount = 0;
        int limitCount = 0;
        int totalRequests = 15; // 注解阈值为10，这里发送15次请求

        // 2. 循环调用，触发 Redis 脚本
        for (int i = 0; i < totalRequests; i++) {
            try {
                testService.doDistributed();
                successCount++;
            } catch (RateLimitException e) {
                limitCount++;
            }
        }

        log.info("分布式限流实测结果|Distributed_limit_real_test,success={},limited={}", successCount, limitCount);

        // 3. 验证结果：1秒内允许10次，超出部分应被拒绝
        // 注意：Redis 性能较高，瓶颈主要来自业务阈值设置
        assertTrue(successCount <= 10, "允许的请求数不应超过QPS阈值");
        assertTrue(limitCount > 0, "超出的请求数应被拒绝");

        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * Redis Lua 脚本吞吐基准测试
     *
     * 实现逻辑：
     * 1. 多次调用 Lua 限流脚本。
     * 2. 统计吞吐并输出结果。
     */
    @Test
    @DisplayName("基准测试_RedisLua脚本极限吞吐量")
    public void testRedisLuaThroughput() {
        // 实现思路：
        // 1. 高次数调用脚本并计算吞吐。
        String key = "benchmark_test";
        int limit = 1000000; // 设置极大阈值，确保不触发限流，只测执行速度
        int window = 60;
        int iterations = 2000; // 执行 2000 次

        long start = System.currentTimeMillis();
        for (int i = 0; i < iterations; i++) {
            redisRateLimiter.tryAcquire(key, limit, window);
        }
        long cost = System.currentTimeMillis() - start;

        double ops = (double) iterations / (cost / 1000.0);
        log.info("RedisLua极限吞吐量|Redis_lua_benchmark,ops={},costMs={},iterations={}",
                String.format("%.2f", ops), cost, iterations);
    }

    // ================= 内部测试辅助类 =================

    @TestConfiguration
    /**
     * 测试配置类
     *
     * 类职责：
     * 提供测试所需的控制器与服务Bean。
     *
     * 设计目的：
     * 1. 隔离测试环境，避免影响业务配置。
     *
     * 为什么需要该类：
     * 测试环境需要专用组件以保证可控性与可复现性。
     *
     * 核心实现思路：
     * - 通过 @TestConfiguration 注册测试Bean。
     */
    static class TestConfig {
        /**
         * 注册测试控制器
         *
         * 实现逻辑：
         * 1. 返回测试控制器实例。
         *
         * @return 测试控制器
         */
        @Bean
        public RateLimitTestController rateLimitTestController() {
            // 实现思路：
            // 1. 返回控制器实例供测试使用。
            return new RateLimitTestController();
        }

        /**
         * 注册测试服务
         *
         * 实现逻辑：
         * 1. 返回测试服务实例。
         *
         * @return 测试服务
         */
        @Bean
        public RateLimitTestService rateLimitTestService() {
            // 实现思路：
            // 1. 返回服务实例供测试使用。
            return new RateLimitTestService();
        }
    }

    /**
     * 测试控制器
     *
     * 类职责：
     * 提供接口级限流测试入口。
     *
     * 设计目的：
     * 1. 触发控制层限流切面。
     *
     * 为什么需要该类：
     * 控制层限流必须通过真实接口触发才能验证效果。
     *
     * 核心实现思路：
     * - 提供简单GET接口并绑定限流注解。
     */
    @RestController
    static class RateLimitTestController {
        // 阈值为1，限流阈值较低
        /**
         * 测试接口
         *
         * 实现逻辑：
         * 1. 返回固定响应用于断言。
         *
         * @return 返回结果
         */
        @GetMapping("/test/rate-limit/interface")
        @SimpleRateLimit(qps = "1")
        public String test() {
            // 实现思路：
            // 1. 返回固定字符串。
            return "success";
        }
    }

    /**
     * 测试服务
     *
     * 类职责：
     * 提供服务级与分布式限流测试入口。
     *
     * 设计目的：
     * 1. 触发服务层限流切面。
     *
     * 为什么需要该类：
     * 服务层限流逻辑需要通过真实方法调用验证。
     *
     * 核心实现思路：
     * - 提供两个简单方法绑定不同限流策略。
     */
    static class RateLimitTestService {
        // 使用配置文件中的属性 test.rate.limit = 5
        /**
         * 服务级限流测试方法
         *
         * 实现逻辑：
         * 1. 执行空逻辑，仅用于触发切面。
         */
        @SimpleRateLimit(qps = "${test.rate.limit}")
        public void doSomething() {
            // 实现思路：
            // 1. 模拟业务方法执行。
        }

        /**
         * 分布式限流测试方法
         *
         * 实现逻辑：
         * 1. 执行空逻辑，仅用于触发分布式限流。
         */
        @SimpleRateLimit(qps = "10", type = SimpleRateLimit.LimitType.DISTRIBUTED)
        public void doDistributed() {
            // 实现思路：
            // 1. 模拟分布式业务调用。
        }
    }
}
