package com.hao.redis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.hao.redis.dal.model.WeiboPost;

import java.util.List;
import java.util.Map;

/**
 * 微博业务服务接口
 *
 * 类职责：
 * 提供用户注册、微博发布、互动与榜单查询等核心业务能力。
 *
 * 设计目的：
 * 1. 抽象业务能力与实现细节，便于测试与替换实现。
 * 2. 保持控制层与数据层解耦。
 *
 * 为什么需要该类：
 * 业务能力需要统一入口与边界，接口层是分层架构的关键契约。
 *
 * 核心实现思路：
 * - 通过接口定义业务动作与返回结构。
 * - 具体实现集中处理 Redis 读写与业务组合。
 */
public interface WeiboService {

    /**
     * 注册新用户
     *
     * 实现逻辑：
     * 1. 使用全局发号器生成用户ID。
     * 2. 将用户基础信息写入 Redis 哈希。
     *
     * @param nickname 昵称
     * @param intro 简介
     * @return 新用户ID
     */
    Integer createUser(String nickname, String intro);

    /**
     * 获取用户详情
     *
     * 实现逻辑：
     * 1. 从 Redis 哈希读取用户信息。
     *
     * @param userId 用户ID
     * @return 用户信息映射
     */
    Map<String, String> getUser(String userId);

    /**
     * 获取全站 UV
     *
     * 实现逻辑：
     * 1. 读取全站 UV 计数器并转换为整数。
     *
     * @return 全站 UV 数
     */
    Integer getTotalUV();

    /**
     * 发布微博
     *
     * 实现逻辑：
     * 1. 生成微博ID并补全发布时间等字段。
     * 2. 写入微博详情并追加到时间轴。
     *
     * @param userId 发布用户ID
     * @param body 微博内容
     * @return 微博ID
     * @throws JsonProcessingException JSON 序列化异常
     */
    String createPost(String userId, WeiboPost body) throws JsonProcessingException;

    /**
     * 获取最新动态列表
     *
     * 实现逻辑：
     * 1. 读取时间轴列表并反序列化为对象列表。
     *
     * @return 最新微博列表
     */
    List<WeiboPost> listLatestPosts();

    /**
     * 点赞微博
     *
     * 实现逻辑：
     * 1. 更新热搜排行榜分数。
     *
     * @param userId 点赞用户ID
     * @param postId 微博ID
     * @return 是否成功
     */
    Boolean likePost(String userId, String postId);

    /**
     * 获取全站热搜排行榜 (Top 10)
     *
     * 实现逻辑：
     * 1. 读取排行榜 Top 10 并组装微博详情列表。
     *
     * @return 热搜榜列表
     */
    List<WeiboPost> getHotRank();
}
