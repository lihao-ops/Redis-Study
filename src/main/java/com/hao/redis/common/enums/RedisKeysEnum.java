package com.hao.redis.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Redis键枚举定义
 *
 * 类职责：
 * 统一管理 Redis 业务键与说明，避免硬编码散落在各处。
 *
 * 设计目的：
 * 1. 规范键命名，便于运维与排障。
 * 2. 为业务代码提供可读的键入口。
 *
 * 为什么需要该类：
 * 统一的键规范可以降低冲突与误用风险，提升可维护性。
 *
 * 核心实现思路：
 * - 枚举承载键与描述信息。
 * - 提供拼接方法统一生成业务键。
 */
@Getter
@AllArgsConstructor
public enum RedisKeysEnum {

    // ============================
    // 1. 全局计数与发号器（字符串）
    // ============================
    /**
     * 全站 UV 总计数器
     * 类型：字符串
     * 用法：INCR total:uv
     */
    TOTAL_UV("total:uv", "全站UV总数"),

    /**
     * 用户 ID 全局发号器
     * 类型：字符串
     * 用法：INCR global:userid -> 返回 1001, 1002...
     */
    GLOBAL_USER_ID("global:userid", "用户ID生成器"),

    /**
     * 微博 ID 全局发号器 (新增补充)
     * 类型：字符串
     * 用法：INCR global:postid -> 返回 5001, 5002...
     */
    GLOBAL_POST_ID("global:postid", "微博ID生成器"),


    // ============================
    // 2. 核心业务数据（哈希与列表）
    // ============================
    /**
     * 用户信息前缀
     * 类型：哈希
     * 用法：拼接 userId -> "user:1001"
     */
    USER_PREFIX("user:", "用户信息键前缀"),

    /**
     * 全站最新动态 (时间轴)
     * 类型：列表
     * 用法：LPUSH timeline:global {json}
     */
    TIMELINE_KEY("timeline:global", "全站最新微博列表"),


    // ============================
    // 3. 互动与排行（有序集合）
    // ============================
    /**
     * 全站热搜排行榜
     * 类型：有序集合
     * 用法：ZINCRBY rank:hot 1 {postId}
     */
    HOT_RANK_KEY("rank:hot", "全站热搜排行榜"),

    /**
     * 单条微博点赞列表前缀
     * 类型：有序集合
     * 用法：拼接 postId + 后缀 -> "weibo:5001:likes"
     */
    WEIBO_PREFIX("weibo:", "微博业务键前缀"),

    /**
     * 微博详情
     * 类型：哈希
     * 用法：键 -> "weibo:list:微博id"
     */
    WEIBO_POST_INFO("weibo:info", "微博详情字典");


    private final String key;
    private final String desc;

    /**
     * 拼接业务键
     *
     * 实现逻辑：
     * 1. 在枚举前缀后拼接业务后缀。
     *
     * @param suffix 业务后缀
     * @return 拼接后的完整键
     */
    public String join(Object suffix) {
        // 实现思路：
        // 1. 使用前缀与后缀拼接生成完整键。
        return this.key + suffix;
    }
}
