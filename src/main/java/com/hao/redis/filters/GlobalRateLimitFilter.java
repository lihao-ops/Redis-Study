package com.hao.redis.filters;

import com.hao.redis.common.constants.RateLimitConstants;
import com.hao.redis.common.exception.RateLimitException;
import com.hao.redis.common.interceptor.SimpleRateLimiter;
import com.hao.redis.common.util.RedisRateLimiter;
import jakarta.servlet.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 全局限流过滤器
 *
 * 类职责：
 * 对进入服务的所有 HTTP 请求进行统一的流量控制。
 *
 * 设计目的：
 * 1. 系统级保护：防止突发流量（如 DDoS、爬虫）耗尽系统资源（线程池、数据库连接）。
 * 2. 默认开启：作为系统的“安全底座”，无需在每个接口上单独配置。
 *
 * 为什么需要该类：
 * 当系统流量在入口处缺乏硬性阈值控制时，任何下游资源都会被动承压并放大故障。
 *
 * 架构深度解析：为什么“单机限流”必须是第一道防线？（漏斗模型）
 * -----------------------------------------------------------------------
 * 1. 成本对比：
 *    - 单机限流（Guava）：纯内存操作，耗时纳秒级，无网络开销。
 *    - 分布式限流（Redis）：网络 IO 操作，耗时毫秒级，消耗 Redis 连接数和带宽。
 *
 * 2. 场景推演（DDoS 攻击）：
 *    假设服务遭遇 10 万 QPS 攻击，而 Redis 集群极限只能抗 5 万 QPS。
 *    - 若分布式优先：10 万请求全部打到 Redis -> Redis 崩溃 -> 整个系统瘫痪（架构设计事故）。
 *    - 若单机优先：假设单机限流 1000 QPS * 10 台机器 = 1 万 QPS 放行。
 *      剩余 9 万请求在本地内存被极速拒绝。Redis 仅承受 1 万 QPS 压力 -> 系统安然无恙。
 *
 * 3. 总结：
 *    单机限流不仅是保护应用本身，更是保护 Redis 基础设施不被流量洪峰冲垮的关键屏障。
 * -----------------------------------------------------------------------
 *
 * 核心实现思路：
 * - 采用过滤器机制，在请求进入 DispatcherServlet 之前拦截。
 * - 策略：单机限流（Guava）优先 + 分布式限流（Redis）兜底。
 * - 阈值：使用 RateLimitConstants.GLOBAL_SERVICE_QPS。
 */
@Slf4j
@Component
@Order(1) // 保证优先级最高，最先执行
public class GlobalRateLimitFilter implements Filter {

    @Autowired
    private SimpleRateLimiter simpleRateLimiter;

    @Autowired
    private RedisRateLimiter redisRateLimiter;

    private static final String GLOBAL_LIMIT_KEY = "global_service_limit";

    // 从配置读取全局QPS，默认使用常量阈值
    @Value("${rate.limit.global-qps:" + RateLimitConstants.GLOBAL_SERVICE_QPS + "}")
    private double globalQps;

    /**
     * 全局限流拦截入口
     *
     * 实现逻辑：
     * 1. 执行单机限流，快速拒绝超载请求。
     * 2. 执行分布式限流，统一控制集群总流量。
     * 3. 全部通过后放行请求链。
     *
     * @param request 请求对象
     * @param response 响应对象
     * @param chain 过滤器链
     * @throws IOException IO 异常
     * @throws ServletException Servlet 异常
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        // 实现思路：
        // 1. 先做单机快速失败，保护当前节点。
        // 2. 再做分布式全局控制，保护集群总量。
        // 3. 放行链路时保持最小副作用。
        // 1. 第一道防线：单机限流 (Guava)
        // 快速失败：如果当前节点负载过高，直接在内存中拒绝，避免浪费 Redis 资源。
        if (!simpleRateLimiter.tryAcquire(GLOBAL_LIMIT_KEY, globalQps)) {
            log.warn("全局单机限流触发|Global_standalone_limit_triggered,qps={}", globalQps);
            throw new RateLimitException("系统繁忙_单机限流");
        }

        // 2. 第二道防线：分布式限流 (Redis)
        // 全局协调：控制整个集群的总流量。
        // 注意：RedisRateLimiter 内部已实现本地保守限流降级，保障异常场景可用性。
        if (!redisRateLimiter.tryAcquire(GLOBAL_LIMIT_KEY, (int) globalQps, 1)) {
            log.warn("全局分布式限流触发|Global_distributed_limit_triggered,qps={}", globalQps);
            throw new RateLimitException("系统繁忙_集群限流");
        }

        // 放行
        chain.doFilter(request, response);
    }
}
