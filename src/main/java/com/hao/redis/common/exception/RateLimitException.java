package com.hao.redis.common.exception;

/**
 * 限流异常定义
 *
 * 类职责：
 * 定义系统限流触发时的特定异常类型，用于中断业务流程。
 *
 * 设计目的：
 * 1. 区分业务异常与系统保护性异常（限流），便于监控系统单独统计。
 * 2. 配合 GlobalExceptionHandler 实现统一的 HTTP 429 返回。
 *
 * 为什么需要该类：
 * 限流属于可预期异常，使用专用异常类型便于分级处理与监控统计。
 *
 * 实现思路：
 * - 继承 RuntimeException，属于非受检异常，业务层无需显式捕获。
 * - 仅包含基础构造方法，传递限流提示信息。
 */
public class RateLimitException extends RuntimeException {

    /**
     * 限流异常构造方法
     *
     * 实现逻辑：
     * 1. 传递限流提示信息给父类。
     *
     * @param message 限流提示
     */
    public RateLimitException(String message) {
        // 实现思路：
        // 1. 调用父类构造方法保存异常信息。
        super(message);
    }
}
