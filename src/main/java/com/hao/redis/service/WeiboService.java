package com.hao.redis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.hao.redis.dal.model.WeiboPost;

import java.util.List;
import java.util.Map;

/**
 * Application-level operations for Weibo posts.
 */
public interface WeiboService {

    /**
     * 注册新用户
     * Redis: INCR global:userid -> HMSET user:id
     * 返回新用户id
     */
    Integer createUser(String nickname, String intro);

    /**
     * 获取用户详情
     * Redis: HGETALL user:id
     */
    Map<String, String> getUser(String userId);

    /**
     * 查看全站 UV (验证之前的拦截器效果)
     * Redis: GET total:uv
     */
    Integer getTotalUV();

    /**
     * 发布微博
     * Redis: INCR -> LPUSH timeline
     * 注意：userId 通常从 Header 或 Token 中获取，模拟登录状态
     */
    String createPost(String userId, WeiboPost body) throws JsonProcessingException;

    /**
     * 获取最新动态列表
     * Redis: LRANGE timeline 0 19
     */
    List<WeiboPost> listLatestPosts();

    /**
     * 点赞微博
     * Redis: ZADD weibo:likes (去重) + ZINCRBY rank:hot (加热度)
     */
    Boolean likePost(String userId, String postId);

    /**
     * 获取全站热搜排行榜 (Top 10)
     * Redis: ZREVRANGE rank:hot 0 9
     */
    List<WeiboPost> getHotRank();
}
