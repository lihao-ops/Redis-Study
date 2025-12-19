package com.hao.redis.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hao.redis.common.enums.RedisKeysEnum;
import com.hao.redis.dal.model.WeiboPost;
import com.hao.redis.integration.redis.RedisClient;
import com.hao.redis.service.WeiboService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 微博业务服务实现
 *
 * 类职责：
 * 实现用户注册、微博发布、互动与榜单查询等核心业务逻辑。
 *
 * 设计目的：
 * 1. 封装 Redis 读写流程，统一业务层行为。
 * 2. 聚合多种 Redis 数据结构以支撑微博业务场景。
 *
 * 为什么需要该类：
 * 业务逻辑需要集中实现，避免控制层直接操作 Redis。
 *
 * 核心实现思路：
 * - 使用全局发号器生成用户与微博ID。
 * - 使用 Hash 存储详情、List 构建时间轴、ZSet 构建热榜。
 * - JSON 序列化用于对象存储与传输。
 */
@Slf4j
@Service
public class WeiboServiceImpl implements WeiboService {

    @Autowired
    private RedisClient<String> redisClient;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 注册新用户
     *
     * 实现逻辑：
     * 1. 使用全局发号器生成用户ID。
     * 2. 构造用户信息并写入 Redis 哈希。
     *
     * @param nickname 昵称
     * @param intro 简介
     * @return 新用户ID
     */
    @Override
    public Integer createUser(String nickname, String intro) {
        // 实现思路：
        // 1. 生成用户ID并写入用户信息。
        // 核心代码：生成用户ID
        Integer newUserId = redisClient.incr(RedisKeysEnum.GLOBAL_USER_ID.getKey()).intValue();
        // 构造用户信息并写入 Redis
        Map<String, String> paramMap = new HashMap<>();
        paramMap.put("userId", String.valueOf(newUserId)); // 冗余存一份 ID，便于查询
        paramMap.put("nickname", nickname);
        paramMap.put("intro", intro);
        paramMap.put("avatar", "default_head.png"); // 默认头像
        paramMap.put("fans", "0");    // 初始粉丝 0
        paramMap.put("follows", "0"); // 初始关注 0
        // 核心代码：写入用户信息
        redisClient.hmset(RedisKeysEnum.USER_PREFIX.join(newUserId), paramMap);
        return newUserId;
    }

    /**
     * 获取用户详情
     *
     * 实现逻辑：
     * 1. 从 Redis 哈希中读取用户信息。
     *
     * @param userId 用户ID
     * @return 用户信息
     */
    @Override
    public Map<String, String> getUser(String userId) {
        // 实现思路：
        // 1. 通过用户ID读取用户哈希信息。
        // 核心代码：读取用户哈希
        return redisClient.hgetAll(RedisKeysEnum.USER_PREFIX.join(userId));
    }

    /**
     * 获取全站 UV
     *
     * 实现逻辑：
     * 1. 读取 UV 计数器并转换为整数。
     *
     * @return 全站 UV 数
     */
    @Override
    public Integer getTotalUV() {
        // 实现思路：
        // 1. 读取 UV 计数器并处理空值。
        String uv = redisClient.get(RedisKeysEnum.TOTAL_UV.getKey());
        // 如果是 null，就返回 0
        return uv == null ? 0 : Integer.parseInt(uv);
    }

    /**
     * 发布微博
     *
     * 实现逻辑：
     * 1. 生成微博ID并补全基础字段。
     * 2. 写入微博详情并追加到时间轴列表。
     *
     * @param userId 发布用户ID
     * @param body 微博内容
     * @return 微博ID
     * @throws JsonProcessingException JSON 序列化异常
     */
    @Override
    public String createPost(String userId, WeiboPost body) throws JsonProcessingException {
        // 实现思路：
        // 1. 生成微博ID并补全字段。
        // 2. 写入详情与时间轴。
        // 核心代码：生成微博ID
        String postId = redisClient.incr(RedisKeysEnum.GLOBAL_POST_ID.getKey()).toString();
        // 补全对象属性
        body.setPostId(postId);
        body.setUserId(userId);
        body.setCreateTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        // 核心代码：序列化微博内容
        String objectValue = objectMapper.writeValueAsString(body);
        // 核心代码：写入微博详情
        redisClient.hset(RedisKeysEnum.WEIBO_POST_INFO.getKey(), postId, objectValue);
        // 核心代码：写入时间轴
        redisClient.lpush(RedisKeysEnum.TIMELINE_KEY.getKey(), objectValue);
        return postId;
    }

    /**
     * 获取最新动态列表
     *
     * 实现逻辑：
     * 1. 读取时间轴列表。
     * 2. 反序列化为微博对象并过滤异常数据。
     *
     * @return 最新微博列表
     */
    @Override
    public List<WeiboPost> listLatestPosts() {
        // 实现思路：
        // 1. 读取时间轴列表。
        // 2. 反序列化并过滤异常数据。
        // 核心代码：读取时间轴
        List<String> lrange = redisClient.lrange(RedisKeysEnum.TIMELINE_KEY.getKey(), 0, 19);
        return lrange.stream()
                .map(item -> {
                    try {
                        return objectMapper.readValue(item, WeiboPost.class);
                    } catch (JsonProcessingException e) {
                        log.error("微博内容解析失败|Weibo_content_parse_fail,item={}", item, e);
                        return null;
                    }
                })
                .filter(Objects::nonNull) // 过滤解析失败的数据
                .toList();
    }

    /**
     * 点赞微博
     *
     * 实现逻辑：
     * 1. 累加热搜排行榜分值。
     *
     * @param userId 点赞用户ID
     * @param postId 微博ID
     * @return 是否成功
     */
    @Override
    public Boolean likePost(String userId, String postId) {
        // 实现思路：
        // 1. 累加微博热度分数。
        // 核心代码：更新热度分数
        redisClient.zincrby(RedisKeysEnum.HOT_RANK_KEY.getKey(), 1, postId);
        return true;
    }

    /**
     * 获取全站热搜排行榜
     *
     * 实现逻辑：
     * 1. 读取热搜榜 Top 10 的微博ID列表。
     * 2. 组装微博详情列表返回。
     *
     * @return 热搜榜列表
     */
    @Override
    public List<WeiboPost> getHotRank() {
        // 实现思路：
        // 1. 获取热搜榜ID列表。
        // 2. 逐条加载微博详情。
        List<WeiboPost> list = new ArrayList<>();
        // 核心代码：读取热搜榜ID
        Set<String> zrevrange = redisClient.zrevrange(RedisKeysEnum.HOT_RANK_KEY.getKey(), 0, 9);
        for (String postId : zrevrange) {
            list.add(getWeiboPost(postId));
        }
        return list;
    }

    /**
     * 获取微博详情
     *
     * 实现逻辑：
     * 1. 从 Redis 哈希读取微博详情字符串。
     * 2. 反序列化为微博对象并返回。
     *
     * @param postId 微博ID
     * @return 微博详情
     */
    public WeiboPost getWeiboPost(String postId) {
        // 实现思路：
        // 1. 读取微博详情并反序列化。
        // 核心代码：读取微博详情
        String postInfoStr = redisClient.hget(RedisKeysEnum.WEIBO_POST_INFO.getKey(), postId);
        if (postInfoStr == null) {
            return null;
        }
        WeiboPost weiboPost = null;
        try {
            weiboPost = objectMapper.readValue(postInfoStr, WeiboPost.class);
        } catch (JsonProcessingException e) {
            log.error("微博详情解析失败|Weibo_detail_parse_fail,postId={}", postId, e);
        }
        return weiboPost;
    }
}
