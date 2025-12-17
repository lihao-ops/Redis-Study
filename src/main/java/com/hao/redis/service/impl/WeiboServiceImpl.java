package com.hao.redis.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hao.redis.common.enums.RedisKeysEnum;
import com.hao.redis.dal.model.WeiboPost;
import com.hao.redis.integration.redis.RedisClient;
import com.hao.redis.service.WeiboService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Default implementation of the WeiboService using MyBatis mapper calls.
 */
@Service
public class WeiboServiceImpl implements WeiboService {

    @Autowired
    private RedisClient<String> redisClient;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 注册新用户
     * Redis: INCR global:userid -> HMSET user:id
     * 返回新用户id
     */
    @Override
    public Integer createUser(String nickname, String intro) {
        //分配新用户id
        Integer newUserId = redisClient.incr(RedisKeysEnum.GLOBAL_USER_ID.getKey()).intValue();
        //插入用户信息到Redis
        Map<String, String> paramMap = new HashMap<>();
        paramMap.put("userId", String.valueOf(newUserId)); // 冗余存一份 ID 在 Hash 里有时候很方便
        paramMap.put("nickname", nickname);
        paramMap.put("intro", intro);
        paramMap.put("avatar", "default_head.png"); // 📷 给个默认头像
        paramMap.put("fans", "0");    // 初始粉丝 0
        paramMap.put("follows", "0"); // 初始关注 0
        redisClient.hmset(RedisKeysEnum.USER_PREFIX.join(newUserId), paramMap);
        return newUserId;
    }

    /**
     * 获取用户详情
     * Redis: HGETALL user:id
     */
    @Override
    public Map<String, String> getUser(String userId) {
        return redisClient.hgetAll(RedisKeysEnum.USER_PREFIX.join(userId));
    }

    /**
     * 查看全站 UV (验证之前的拦截器效果)
     * Redis: GET total:uv
     */
    @Override
    public Integer getTotalUV() {
        String uv = redisClient.get(RedisKeysEnum.TOTAL_UV.getKey());
        // 如果是 null，就返回 0
        return uv == null ? 0 : Integer.parseInt(uv);
    }

    /**
     * 发布微博
     * Redis: INCR -> LPUSH timeline
     * 注意：userId 通常从 Header 或 Token 中获取，模拟登录状态
     */
    @Override
    public String createPost(String userId, WeiboPost body) throws JsonProcessingException {
        String postId = redisClient.incr(RedisKeysEnum.GLOBAL_POST_ID.getKey()).toString();
        //补全对象属性
        body.setPostId(postId);
        body.setUserId(userId);
        body.setCreateTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        String objectValue = objectMapper.writeValueAsString(body);
        redisClient.hset(RedisKeysEnum.WEIBO_POST_INFO.getKey(), postId, objectValue);
        redisClient.lpush(RedisKeysEnum.TIMELINE_KEY.getKey(), objectValue);
        return postId;
    }

    /**
     * 获取最新动态列表
     * Redis: LRANGE timeline 0 19
     */
    @Override
    public List<WeiboPost> listLatestPosts() {
        List<String> lrange = redisClient.lrange(RedisKeysEnum.TIMELINE_KEY.getKey(), 0, 19);
        return lrange.stream()
                .map(item -> {
                    try {
                        return objectMapper.readValue(item, WeiboPost.class);
                    } catch (JsonProcessingException e) {
                        e.printStackTrace();
                        return null;
                    }
                })
                .filter(Objects::nonNull) // 👈 加上这句，过滤掉解析失败的数据
                .toList();
    }

    /**
     * 点赞微博
     * Redis: ZADD weibo:likes (去重) + ZINCRBY rank:hot (加热度)
     */
    @Override
    public Boolean likePost(String userId, String postId) {
        redisClient.zincrby(RedisKeysEnum.HOT_RANK_KEY.getKey(), 1, postId);
        return true;
    }

    /**
     * 获取全站热搜排行榜 (Top 10)
     * Redis: ZREVRANGE rank:hot 0 9
     */
    @Override
    public List<WeiboPost> getHotRank() {
        List<WeiboPost> list = new ArrayList<>();
        Set<String> zrevrange = redisClient.zrevrange(RedisKeysEnum.HOT_RANK_KEY.getKey(), 0, 9);
        for (String postId : zrevrange) {
            list.add(getWeiboPost(postId));
        }
        return list;
    }

    /**
     * 获取微博详情
     *
     * @param postId 微博id
     * @return 微博详情
     */
    public WeiboPost getWeiboPost(String postId) {
        String postInfoStr = redisClient.hget(RedisKeysEnum.WEIBO_POST_INFO.getKey(), postId);
        WeiboPost weiboPost = null;
        try {
            weiboPost = objectMapper.readValue(postInfoStr, WeiboPost.class);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return weiboPost;
    }
}
