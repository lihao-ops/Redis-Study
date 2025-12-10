package com.hao.redisstudy.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum RedisKeysEnum {

    // ============================
    // 1. 全局计数与发号器 (String)
    // ============================
    /**
     * 全站 UV 总计数器
     * 类型: String
     * 用法: INCR total:uv
     */
    TOTAL_UV("total:uv", "全站UV总数"),

    /**
     * 用户 ID 全局发号器
     * 类型: String
     * 用法: INCR global:userid -> 返回 1001, 1002...
     */
    GLOBAL_USER_ID("global:userid", "用户ID生成器"),

    /**
     * 微博 ID 全局发号器 (新增补充)
     * 类型: String
     * 用法: INCR global:postid -> 返回 5001, 5002...
     */
    GLOBAL_POST_ID("global:postid", "微博ID生成器"),


    // ============================
    // 2. 核心业务数据 (Hash & List)
    // ============================
    /**
     * 用户信息前缀
     * 类型: Hash
     * 用法: 拼接 userId -> "user:1001"
     */
    USER_PREFIX("user:", "用户信息Key前缀"),

    /**
     * 全站最新动态 (时间轴)
     * 类型: List
     * 用法: LPUSH timeline:global {json}
     */
    TIMELINE_KEY("timeline:global", "全站最新微博列表"),


    // ============================
    // 3. 互动与排行 (ZSet)
    // ============================
    /**
     * 全站热搜排行榜
     * 类型: ZSet
     * 用法: ZINCRBY rank:hot 1 {postId}
     */
    HOT_RANK_KEY("rank:hot", "全站热搜排行榜"),

    /**
     * 单条微博点赞列表前缀
     * 类型: ZSet
     * 用法: 拼接 postId + 后缀 -> "weibo:5001:likes"
     */
    WEIBO_PREFIX("weibo:", "微博业务Key前缀"),

    /**
     * 微博详情
     * 类型: hash
     * 用法: key -> "weibo:list:微博id"
     */
    WEIBO_POST_INFO("weibo:info", "微博详情字典");


    private final String key;
    private final String desc;

    /**
     * 简单的拼接工具方法
     * 例子: RedisKeysEnum.USER_PREFIX.join("1001") -> "user:1001"
     */
    public String join(Object suffix) {
        return this.key + suffix;
    }
}