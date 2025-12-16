package com.hao.redisstudy.common.aspect;

import com.hao.redisstudy.common.exception.RateLimitException;
import com.hao.redisstudy.common.interceptor.SimpleRateLimiter;
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
 * 限流切面
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

    @Around("@annotation(limit)")
    public Object around(ProceedingJoinPoint point, SimpleRateLimit limit) throws Throwable {
        // 获取请求路径作为限流 key
        String key = getRequestUri();
        double qps = parseQps(limit.qps());
        // 尝试获取令牌
        boolean allowed = rateLimiter.tryAcquire(key, qps);
        if (!allowed) {
            log.warn("请求被限流:uri={},qps={}", key, qps);
            throw new RateLimitException(limit.message());
        }

        // 放行
        return point.proceed();
    }

    /**
     * 解析QPS值，支持 ${property} 格式和直接数字
     */
    private double parseQps(String qpsStr) {
        if (qpsStr.startsWith("${") && qpsStr.endsWith("}")) {
            String propertyName = qpsStr.substring(2, qpsStr.length() - 1);
            String value = environment.getProperty(propertyName);
            if (value != null) {
                try {
                    // 此日志每个配置项只打印一次
                    if (loggedProperties.add(propertyName)) {
                        log.info("SimpleRateLimitAspect loaded config: {}={}", propertyName, value);
                    }
                    return Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    log.error("配置值无法解析为数字: {}={}", propertyName, value);
                    return 100.0;
                }
            }
            if (loggedProperties.add(propertyName)) {
                log.warn("未找到配置: {}, 使用默认值100", propertyName);
            }
            return 100.0;
        }

        try {
            return Double.parseDouble(qpsStr);
        } catch (NumberFormatException e) {
            log.error("QPS值无法解析: {}, 使用默认值100", qpsStr);
            return 100.0;
        }
    }

    private String getRequestUri() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            return request.getRequestURI();
        }
        return "unknown";
    }
}