package com.hao.redis.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.util.concurrent.RateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 本地缓存配置类
 *
 * 类职责：
 * 统一管理和配置项目中使用的 Caffeine 本地缓存实例。
 *
 * 设计目的：
 * 1. 集中配置：将缓存的容量、过期策略等参数集中管理，便于调优和维护。
 * 2. 依赖注入：将配置好的 Cache 实例作为 Bean 注入到需要使用的组件中，符合 Spring 的设计思想。
 * 3. 差异化配置：可为不同场景（如限流器、业务数据）提供不同配置的 Cache 实例。
 *
 * 为什么需要该类：
 * 避免在业务代码中分散地构建 Cache 实例，导致配置不一致和难以管理。
 */
@Configuration
public class CacheConfig {

    /**
     * 限流器缓存实例
     *
     * 实现逻辑：
     * 1. 使用 Caffeine 构建一个专门用于存储 RateLimiter 对象的缓存。
     * 2. 配置淘汰策略，以应对恶意攻击和节省内存。
     *
     * @return 配置好的限流器 Cache Bean
     */
    @Bean("rateLimiterCache")
    public Cache<String, RateLimiter> rateLimiterCache() {
        // 实现思路：
        // 1. expireAfterAccess: 用户在指定时间内无任何请求，则其限流器被自动回收，释放内存。
        // 2. maximumSize: 限制缓存的最大条目数，防止因 Key 无限增长（如随机ID攻击）导致OOM。
        // 3. W-TinyLFU 算法: Caffeine 的核心优势，能有效识别并淘汰低频访问的“垃圾”数据（如扫描攻击），
        //    保护高频访问的正常用户的缓存不被污染。
        return Caffeine.newBuilder()
                .expireAfterAccess(10, TimeUnit.MINUTES) // 用户停止活动10分钟后，自动移除其限流器
                .maximumSize(50000) // 根据服务器内存调整，保护系统不被撑爆
                .build();
    }
}
