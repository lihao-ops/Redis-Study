package com.hao.redis.common.constants;

/**
 * 限流阈值常量定义
 *
 * 类职责：
 * 集中管理系统中的限流阈值（QPS），避免魔法数字，便于统一调整与维护。
 *
 * 使用说明：
 * 配合 @SimpleRateLimit 注解使用。
 */
public class RateLimitConstants {

    /**
     * 全局服务限流阈值 (QPS)
     * 作用：保护整个服务集群不被突发流量打垮，作为系统的最后一道防线。
     * todo 最大稳定 QPS，而不是最大极限 QPS
     * 在不排队、不抖动、不堆积、P95 < 200m 的情况下的最大 QPS
     * | QPS阈值 | Avg Latency | P95    | 状态   |
     * | ----- | ----------- | ------ | ---- |
     * | 600   | <100ms      | <150ms | ✔稳   |
     * | 800   | <150ms      | <200ms | ✔稳   |
     * | 1000  | <200ms      | ~300ms | ⚠️轻压 |
     * | 1200  | >500ms      | ~800ms | ❌排队  |
     *
     */
    public static final String GLOBAL_SERVICE_QPS = "2500";

    /**
     * 微博发布接口限流阈值 (QPS)
     * 业务场景：写操作，涉及数据库与Redis多重写入，资源消耗大，阈值设为 10
     */
    public static final String WEIBO_CREATE_QPS = "10";

    private RateLimitConstants() {
        // 禁止实例化
    }
}