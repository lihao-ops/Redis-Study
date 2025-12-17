package com.hao.redis.common.exception;

/**
 * 限流自定义异常
 */
public class RateLimitException extends RuntimeException {
    public RateLimitException(String message) {
        super(message);
    }
}