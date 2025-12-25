package com.hao.redis.common.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Redis 逻辑过期封装类
 * <p>
 * 类职责：
 * 用于封装业务数据和逻辑过期时间，解决缓存击穿问题。
 * <p>
 * 设计目的：
 * 1. 将数据与过期时间解耦，Redis Key 设置为永不过期。
 * 2. 业务层通过 expireTime 判断是否需要异步重建。
 *
 * @param <T> 业务数据类型
 */
@Data
@NoArgsConstructor
public class RedisLogicalData<T> {

    /**
     * 逻辑过期时间
     */
    private LocalDateTime expireTime;

    /**
     * 实际业务数据
     */
    private T data;

    /**
     * 构造方法
     *
     * @param expireSeconds 逻辑过期秒数（从当前时间往后推）
     * @param data 业务数据
     */
    public RedisLogicalData(long expireSeconds, T data) {
        this.data = data;
        this.expireTime = LocalDateTime.now().plusSeconds(expireSeconds);
    }
}
