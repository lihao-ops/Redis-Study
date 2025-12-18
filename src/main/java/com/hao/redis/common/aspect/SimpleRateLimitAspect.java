package com.hao.redis.common.aspect;

import com.hao.redis.common.exception.RateLimitException;
import com.hao.redis.common.interceptor.SimpleRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单机限流切面
 *
 * 类职责：
 * 拦截带有 @SimpleRateLimit 注解的方法，执行单机维度的令牌桶限流检查。
 *
 * 设计目的：
 * 1. 业务解耦：将限流逻辑从业务代码中剥离，通过注解声明式使用。
 * 2. 系统保护：防止单机过载，确保服务在极端流量下的可用性。
 *
 * 实现思路：
 * - 使用 Spring AOP @Around 环绕通知拦截目标方法。
 * - 解析注解中的 QPS 配置（支持动态配置）。
 * - 调用 SimpleRateLimiter (Guava RateLimiter封装) 尝试获取令牌。
 * - 获取失败则抛出 RateLimitException，由全局异常处理器兜底。
 */
@Slf4j
@Aspect
@Component
public class SimpleRateLimitAspect {

    @Autowired
    private Environment environment;

    @Autowired
    private SimpleRateLimiter rateLimiter;

    /**
     * 记录已打印过的配置Key，防止重复打印，同时支持多个不同的配置Key
     */
    private final Set<String> loggedProperties = ConcurrentHashMap.newKeySet();

    /**
     * 环绕通知处理限流逻辑
     *
     * 实现逻辑：
     * 1. 获取请求 URI 作为限流资源 Key。
     * 2. 解析注解 QPS 参数。
     * 3. 调用限流器尝试获取令牌。
     * 4. 失败则记录日志并抛出异常，成功则放行。
     *
     * @param point 切点
     * @param limit 注解对象
     * @return 业务执行结果
     * @throws Throwable 异常
     */
    @Around("@annotation(limit)")
    public Object around(ProceedingJoinPoint point, SimpleRateLimit limit) throws Throwable {
        // 实现思路：
        // 1. 获取请求URI与注解配置的QPS。
        // 2. 调用限流器进行检查。
        // 3. 失败抛出异常，成功放行。

        // 获取请求路径作为限流 key
        String key = getRequestUri();
        double qps = parseQps(limit.qps());
        // 尝试获取令牌
        boolean allowed = rateLimiter.tryAcquire(key, qps);
        if (!allowed) {
            log.warn("请求被限流|Request_rate_limited,uri={},qps={}", key, qps);
            throw new RateLimitException(limit.message());
        }

        // 放行
        return point.proceed();
    }

    /**
     * 解析 QPS 配置值
     *
     * 实现逻辑：
     * 1. 判断是否为 ${...} 占位符格式。
     * 2. 若是，从 Environment 中读取配置值并解析为 double。
     * 3. 若否，直接解析字符串为 double。
     * 4. 解析失败或配置不存在时，记录错误日志并返回默认值 100.0。
     *
     * @param qpsStr 注解中的 QPS 字符串
     * @return 解析后的 QPS 值
     */
    private double parseQps(String qpsStr) {
        // 实现思路：
        // 1. 识别 ${...} 占位符，从 Environment 读取配置。
        // 2. 解析数字，处理异常与默认值兜底。

        if (qpsStr.startsWith("${") && qpsStr.endsWith("}")) {
            String propertyName = qpsStr.substring(2, qpsStr.length() - 1);
            String value = environment.getProperty(propertyName);
            if (value != null) {
                try {
                    // 此日志每个配置项只打印一次
                    if (loggedProperties.add(propertyName)) {
                        log.info("加载限流配置|Loaded_rate_limit_config,key={},value={}", propertyName, value);
                    }
                    return Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    log.error("配置值解析失败_使用默认值|Config_parse_error_use_default,key={},value={}", propertyName, value);
                    return 100.0;
                }
            }
            if (loggedProperties.add(propertyName)) {
                log.warn("未找到配置_使用默认值|Config_not_found_use_default,key={}", propertyName);
            }
            return 100.0;
        }

        try {
            return Double.parseDouble(qpsStr);
        } catch (NumberFormatException e) {
            log.error("QPS解析失败_使用默认值|Qps_parse_error_use_default,value={}", qpsStr);
            return 100.0;
        }
    }

    /**
     * 获取当前请求 URI
     *
     * 实现逻辑：
     * 1. 通过 RequestContextHolder 获取 ServletRequestAttributes。
     * 2. 提取 HttpServletRequest 对象。
     * 3. 返回 RequestURI，若上下文缺失则返回 "unknown"。
     *
     * @return 请求 URI
     */
    private String getRequestUri() {
        // 实现思路：
        // 1. 从 Spring 上下文获取 Request 对象。
        // 2. 提取 URI，上下文缺失时返回 unknown。

        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            return request.getRequestURI();
        }
        return "unknown";
    }
}