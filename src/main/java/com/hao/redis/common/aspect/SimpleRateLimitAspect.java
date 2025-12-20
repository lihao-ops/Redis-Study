package com.hao.redis.common.aspect;

import com.hao.redis.common.exception.RateLimitException;
import com.hao.redis.common.interceptor.SimpleRateLimiter;
import com.hao.redis.common.util.RedisRateLimiter;
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
import org.springframework.web.servlet.HandlerMapping;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单机限流切面
 *
 * 类职责：
 * 拦截带有 @SimpleRateLimit 注解的方法，执行多级限流检查（单机 + 分布式）。
 *
 * 设计目的：
 * 1. 业务解耦：将限流逻辑从业务代码中剥离，通过注解声明式使用。
 * 2. 多级防护：结合 Guava 单机限流（保护节点）与 Redis 分布式限流（保护下游），实现高可用架构。
 *
 * 为什么需要该类：
 * 限流是横切关注点，集中在切面中才能保证所有入口的一致性与可维护性。
 *
 * 实现思路：
 * - 使用 Spring AOP @Around 环绕通知拦截目标方法。
 * - 解析注解中的 QPS 配置（支持动态配置）。
 * - 第一层：调用 SimpleRateLimiter (Guava) 进行本地快速检查。
 * - 第二层：若配置为 DISTRIBUTED，调用 RedisRateLimiter (Lua) 进行集群总量检查。
 * - 任一环节失败则抛出 RateLimitException。
 */
@Slf4j
@Aspect
@Component
public class SimpleRateLimitAspect {

    @Autowired
    private Environment environment;

    @Autowired
    private SimpleRateLimiter rateLimiter;

    @Autowired
    private RedisRateLimiter redisRateLimiter;

    /**
     * 记录已打印过的配置键，防止重复打印，同时支持多个不同的配置键
     */
    private final Set<String> loggedProperties = ConcurrentHashMap.newKeySet();

    /**
     * 记录已提示过的动态路径模式，避免重复刷日志
     */
    private final Set<String> warnedKeyPatterns = ConcurrentHashMap.newKeySet();

    /**
     * 环绕通知处理限流逻辑
     *
     * 实现逻辑：
     * 1. 解析限流键（优先注解指定，其次使用请求匹配路径）。
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
        // 1. 获取限流键与注解配置的QPS。
        // 2. 调用限流器进行检查。
        // 3. 失败抛出异常，成功放行。

        // 获取限流键
        String key = resolveRateLimitKey(limit);
        double qps = parseQps(limit.qps());

        // 1. 第一道防线：单机限流 (Guava)
        // 无论配置何种类型，始终启用单机限流作为兜底。
        // 作用：保护当前节点不被突发流量打垮，同时在 Redis 故障降级时提供最后一道防线。
        if (!rateLimiter.tryAcquire(key, qps)) {
            log.warn("单机限流拦截|Standalone_rate_limited,uri={},qps={}", key, qps);
            throw new RateLimitException(limit.message());
        }

        // 2. 第二道防线：分布式限流 (Redis)
        // 作用：控制集群总流量，防止下游服务过载。
        if (limit.type() == SimpleRateLimit.LimitType.DISTRIBUTED) {
            // 注意：RedisRateLimiter 内部已实现本地保守限流降级，保障异常场景可用性
            if (!redisRateLimiter.tryAcquire(key, (int) qps, 1)) {
                log.warn("分布式限流拦截|Distributed_rate_limited,uri={},qps={}", key, qps);
                throw new RateLimitException(limit.message());
            }
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
     * 解析限流键
     *
     * 实现逻辑：
     * 1. 优先读取注解中指定的限流键。
     * 2. 读取请求匹配路径作为稳定限流键。
     * 3. 无法获取路径时回退为请求URI或unknown。
     *
     * @param limit 限流注解
     * @return 限流键
     */
    private String resolveRateLimitKey(SimpleRateLimit limit) {
        // 实现思路：
        // 1. 优先使用注解指定的键。
        // 2. 尝试使用请求匹配路径。
        // 3. 兜底使用请求URI。

        String explicitKey = limit.key();
        if (explicitKey != null && !explicitKey.trim().isEmpty()) {
            return explicitKey.trim();
        }

        HttpServletRequest request = getCurrentRequest();
        if (request == null) {
            return "unknown";
        }

        String pattern = getBestMatchingPattern(request);
        if (pattern != null) {
            if (pattern.contains("{") && warnedKeyPatterns.add(pattern)) {
                log.warn("限流键包含路径变量_建议显式指定|Rate_limit_key_contains_path_variable_suggest_explicit_key,pattern={}", pattern);
            }
            return pattern;
        }

        String requestUri = request.getRequestURI();
        return requestUri != null ? requestUri : "unknown";
    }

    /**
     * 获取当前请求对象
     *
     * 实现逻辑：
     * 1. 通过 RequestContextHolder 获取 ServletRequestAttributes。
     * 2. 返回 HttpServletRequest，缺失时返回 null。
     *
     * @return 请求对象
     */
    private HttpServletRequest getCurrentRequest() {
        // 实现思路：
        // 1. 从 Spring 上下文获取请求对象。

        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            return attributes.getRequest();
        }
        return null;
    }

    /**
     * 获取请求匹配路径
     *
     * 实现逻辑：
     * 1. 读取 Spring MVC 匹配路径属性。
     * 2. 无匹配路径时返回 null。
     *
     * @param request 请求对象
     * @return 匹配路径
     */
    private String getBestMatchingPattern(HttpServletRequest request) {
        // 实现思路：
        // 1. 获取请求匹配路径属性。
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String) {
            return (String) pattern;
        }
        return null;
    }
}
