package com.hao.redis.common.aspect;

import com.hao.redis.common.exception.RateLimitException;
import com.hao.redis.common.interceptor.SimpleRateLimiter;
import com.hao.redis.common.util.RedisRateLimiter;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 分布式限流切面测试
 *
 * 类职责：
 * 验证多级限流切面的调用顺序与异常行为。
 *
 * 测试目的：
 * 1. 验证单机限流与分布式限流的调用顺序。
 * 2. 验证限流触发时是否抛出业务异常。
 *
 * 设计思路：
 * - 使用 Mockito 模拟上下文与依赖组件。
 * - 分别覆盖放行、分布式拦截与单机拦截场景。
 *
 * 为什么需要该类：
 * 切面是限流的核心入口，需通过单元测试确保分支逻辑可靠。
 *
 * 核心实现思路：
 * - 构造请求上下文并注入注解参数。
 * - 通过行为验证确保调用链正确。
 */
@ExtendWith(MockitoExtension.class)
class DistributedRateLimitTest {

    @Mock
    private RedisRateLimiter redisRateLimiter;

    @Mock
    private SimpleRateLimiter simpleRateLimiter;

    @Mock
    private Environment environment;

    @InjectMocks
    private SimpleRateLimitAspect aspect;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private SimpleRateLimit simpleRateLimit;

    @Mock
    private HttpServletRequest request;

    @Mock
    private ServletRequestAttributes requestAttributes;

    /**
     * 分布式限流放行场景
     *
     * 实现逻辑：
     * 1. 构造请求上下文与注解配置。
     * 2. 模拟单机与分布式限流均放行。
     *
     * @throws Throwable 异常
     */
    @Test
    void testDistributedRateLimit_Allowed() throws Throwable {
        // 实现思路：
        // 1. 模拟请求上下文与限流配置。
        // 模拟上下文
        RequestContextHolder.setRequestAttributes(requestAttributes);
        when(requestAttributes.getRequest()).thenReturn(request);
        when(request.getRequestURI()).thenReturn("/test/api");

        // 模拟注解配置：分布式限流，QPS=10
        when(simpleRateLimit.type()).thenReturn(SimpleRateLimit.LimitType.DISTRIBUTED);
        when(simpleRateLimit.qps()).thenReturn("10");

        // 模拟第一道防线（单机）允许通过
        when(simpleRateLimiter.tryAcquire(anyString(), anyDouble())).thenReturn(true);
        // 模拟第二道防线（分布式）允许通过
        when(redisRateLimiter.tryAcquire(anyString(), eq(10), eq(1))).thenReturn(true);

        // 执行切面
        aspect.around(joinPoint, simpleRateLimit);

        // 验证：两道防线都被调用
        verify(simpleRateLimiter, times(1)).tryAcquire(anyString(), anyDouble());
        verify(redisRateLimiter, times(1)).tryAcquire(anyString(), eq(10), eq(1));
        // 验证：业务方法被成功执行
        verify(joinPoint, times(1)).proceed();
    }

    /**
     * 分布式限流拦截场景
     *
     * 实现逻辑：
     * 1. 模拟单机放行与分布式拒绝。
     * 2. 验证抛出限流异常并阻断业务执行。
     *
     * @throws Throwable 异常
     */
    @Test
    @DisplayName("多级限流_分布式拦截")
    void testDistributedRateLimit_Blocked() throws Throwable {
        // 实现思路：
        // 1. 先放行单机限流，再触发分布式拒绝。
        RequestContextHolder.setRequestAttributes(requestAttributes);
        when(requestAttributes.getRequest()).thenReturn(request);
        when(request.getRequestURI()).thenReturn("/test/api");
        when(simpleRateLimit.type()).thenReturn(SimpleRateLimit.LimitType.DISTRIBUTED);
        when(simpleRateLimit.qps()).thenReturn("10");
        when(simpleRateLimit.message()).thenReturn("Limited");

        // 模拟第一道防线（单机）允许通过
        when(simpleRateLimiter.tryAcquire(anyString(), anyDouble())).thenReturn(true);
        // 模拟第二道防线（分布式）拒绝
        when(redisRateLimiter.tryAcquire(anyString(), eq(10), eq(1))).thenReturn(false);

        // 验证：因分布式限流触发，抛出异常
        assertThrows(RateLimitException.class, () -> aspect.around(joinPoint, simpleRateLimit));
        // 验证：业务方法未被执行
        verify(joinPoint, never()).proceed();
    }

    /**
     * 单机限流拦截场景
     *
     * 实现逻辑：
     * 1. 模拟单机限流拒绝。
     * 2. 验证分布式限流未被调用。
     *
     * @throws Throwable 异常
     */
    @Test
    @DisplayName("多级限流_单机拦截")
    void testStandaloneRateLimit_Blocked() throws Throwable {
        // 实现思路：
        // 1. 直接触发单机限流拒绝分支。
        RequestContextHolder.setRequestAttributes(requestAttributes);
        when(requestAttributes.getRequest()).thenReturn(request);
        when(request.getRequestURI()).thenReturn("/test/api");
        when(simpleRateLimit.qps()).thenReturn("10");
        when(simpleRateLimit.message()).thenReturn("Limited");

        // 模拟第一道防线（单机）直接拒绝
        when(simpleRateLimiter.tryAcquire(anyString(), anyDouble())).thenReturn(false);

        // 验证：因单机限流触发，抛出异常
        assertThrows(RateLimitException.class, () -> aspect.around(joinPoint, simpleRateLimit));
        // 验证：分布式限流器未被调用，请求被提前拦截
        verify(redisRateLimiter, never()).tryAcquire(anyString(), anyInt(), anyInt());
    }
}
