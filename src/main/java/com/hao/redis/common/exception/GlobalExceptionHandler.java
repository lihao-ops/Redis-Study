package com.hao.redis.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 *
 * 类职责：
 * 统一捕获并处理Controller层抛出的异常，将异常转换为标准化的API响应格式。
 *
 * 设计目的：
 * 1. 屏蔽底层异常细节，防止敏感信息泄露给前端。
 * 2. 统一错误码与错误提示，降低前后端沟通成本。
 * 3. 集中记录异常日志，便于线上故障排查。
 *
 * 实现思路：
 * - 使用 @RestControllerAdvice 切面拦截所有Controller异常。
 * - 针对特定业务异常（如 RateLimitException）进行精确匹配处理。
 * - 使用 Exception 作为兜底策略，捕获未预期的运行时异常。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理限流异常
     *
     * 实现逻辑：
     * 1. 捕获 RateLimitException。
     * 2. 记录 WARN 级别日志（限流属于预期内业务保护，非系统错误）。
     * 3. 返回 HTTP 429 (Too Many Requests) 状态码。
     *
     * @param e 限流异常对象
     * @param request 请求上下文
     * @return 标准化错误响应
     */
    @ExceptionHandler(RateLimitException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Map<String, Object> handleRateLimitException(RateLimitException e, WebRequest request) {
        // 实现思路：
        // 1. 记录告警日志，保留现场信息（如异常消息、请求路径）。
        // 2. 构造对前端友好的返回结构。
        log.warn("触发限流保护|Rate_limit_triggered,path={},message={}", getRequestPath(request), e.getMessage());

        Map<String, Object> result = new HashMap<>();
        result.put("code", HttpStatus.TOO_MANY_REQUESTS.value());
        result.put("message", "访问过于频繁，请稍后再试");
        result.put("error", e.getMessage());
        return result;
    }

    /**
     * 处理系统兜底异常
     *
     * 实现逻辑：
     * 1. 捕获所有未被单独处理的 Exception。
     * 2. 记录 ERROR 级别日志，必须包含堆栈信息与请求路径。
     * 3. 返回 HTTP 500 状态码，提示系统繁忙。
     *
     * @param e 未知异常对象
     * @param request 请求上下文
     * @return 标准化错误响应
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleException(Exception e, WebRequest request) {
        // 实现思路：
        // 1. 必须打印堆栈，否则无法排查线上Bug。
        // 2. 记录请求路径，定位异常触发点。
        // 3. 隐藏具体错误细节，防止安全漏洞暴露。
        log.error("系统未知异常|System_unknown_error,path={},message={}", getRequestPath(request), e.getMessage(), e);

        Map<String, Object> result = new HashMap<>();
        result.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
        result.put("message", "系统繁忙，请联系管理员");
        return result;
    }

    /**
     * 提取纯净的请求路径
     *
     * 实现逻辑：
     * 1. 获取 WebRequest 的简短描述（包含 uri 和 client 信息）。
     * 2. 去除 "uri=" 前缀，仅保留路径部分，保持日志整洁。
     *
     * @param request 请求上下文
     * @return 请求URI，去除冗余前缀
     */
    private String getRequestPath(WebRequest request) {
        // 实现思路：
        // 1. 获取请求描述信息。
        // 2. 剔除冗余前缀，提取纯净URI。

        // WebRequest.getDescription(false) 返回格式通常为 "uri=/path;client=..."
        // 这里简单处理去除 "uri=" 前缀，使日志更整洁
        return request.getDescription(false).replace("uri=", "");
    }
}