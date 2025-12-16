package com.hao.redisstudy.web.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.hao.redisstudy.common.model.WeiboPost;
import com.hao.redisstudy.service.WeiboService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/weibo") // 建议加个统一前缀
@RequiredArgsConstructor
public class WeiboController {

    @Autowired
    private WeiboService weiboService;

    // ===========================
    // 1. 用户模块 (User)
    // ===========================

    /**
     * 注册新用户
     * Redis: INCR global:userid -> HMSET user:id
     * 返回新用户id
     */
    @PostMapping("/user/register")
    public Integer registerUser(@RequestParam String nickname, @RequestParam String intro) {
        return weiboService.createUser(nickname, intro);
    }

    /**
     * 获取用户详情
     * Redis: HGETALL user:id
     */
    @GetMapping("/user/{userId}")
    public Map<String, String> getUser(@PathVariable String userId) {
        return weiboService.getUser(userId);
    }

    // ===========================
    // 2. 微博内容模块 (Content)
    // ===========================

    /**
     * 发布微博
     * Redis: INCR -> LPUSH timeline
     * 注意：userId 通常从 Header 或 Token 中获取，模拟登录状态
     */
    @PostMapping("/weibo")
    public String createPost(@RequestHeader("userId") String userId, @RequestBody WeiboPost body) throws JsonProcessingException {
        return weiboService.createPost(userId, body);
    }

    /**
     * 获取最新动态列表
     * Redis: LRANGE timeline 0 19
     */
    @GetMapping("/weibo/list")
    public List<WeiboPost> listPosts() {
        // 这里假设 Service 层把 JSON 字符串转回了 WeiboPost 对象列表
        return weiboService.listLatestPosts();
    }

    // ===========================
    // 3. 互动与榜单 (Interaction)
    // ===========================

    /**
     * 点赞微博
     * Redis: ZADD weibo:likes (去重) + ZINCRBY rank:hot (加热度)
     */
    @PostMapping("/weibo/{postId}/like")
    public Boolean likePost(@RequestHeader("userId") String userId,
                            @PathVariable String postId) {
        return weiboService.likePost(userId, postId);
    }

    /**
     * 获取全站热搜排行榜 (Top 10)
     * Redis: ZREVRANGE rank:hot 0 9
     */
    @GetMapping("/weibo/rank")
    public List<WeiboPost> getHotRank() {
        // 返回的是 postId 的集合
        return weiboService.getHotRank();
    }

    // ===========================
    // 4. 系统统计 (System)
    // ===========================

    /**
     * 查看全站 UV (验证之前的拦截器效果)
     * Redis: GET total:uv
     */
    @GetMapping("/system/uv")
    public Integer getTotalUV() {
        return weiboService.getTotalUV();
    }
}