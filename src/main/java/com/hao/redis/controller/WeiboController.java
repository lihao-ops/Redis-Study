package com.hao.redis.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.hao.redis.common.aspect.SimpleRateLimit;
import com.hao.redis.common.constants.RateLimitConstants;
import com.hao.redis.dal.model.WeiboPost;
import com.hao.redis.service.WeiboService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 微博业务控制器
 *
 * 类职责：
 * 提供用户、微博内容、互动与统计接口，负责参数接收与请求转发。
 *
 * 设计目的：
 * 1. 统一 HTTP 入口，保持控制层轻量。
 * 2. 与服务层解耦，集中处理请求映射。
 *
 * 为什么需要该类：
 * 控制层是请求入口，需要统一路径与调用链组织。
 *
 * 核心实现思路：
 * - 通过 Spring MVC 映射 REST 接口。
 * - 业务逻辑委托给 WeiboService。
 */
@RestController
@RequestMapping("/weibo") // 统一接口前缀
@RequiredArgsConstructor
public class WeiboController {

    @Autowired
    private WeiboService weiboService;

    // ===========================
    // 1. 用户模块
    // ===========================

    /**
     * 注册新用户
     *
     * 实现逻辑：
     * 1. 接收昵称与简介参数。
     * 2. 调用服务层完成用户注册。
     *
     * @param nickname 昵称
     * @param intro 简介
     * @return 新用户ID
     */
    @PostMapping("/user/register")
    public Integer registerUser(@RequestParam String nickname, @RequestParam String intro) {
        // 实现思路：
        // 1. 直接委托服务层完成注册。
        // 核心代码：调用注册服务
        return weiboService.createUser(nickname, intro);
    }

    /**
     * 获取用户详情
     *
     * 实现逻辑：
     * 1. 接收用户ID并查询用户详情。
     *
     * @param userId 用户ID
     * @return 用户信息
     */
    @GetMapping("/user/{userId}")
    public Map<String, String> getUser(@PathVariable String userId) {
        // 实现思路：
        // 1. 直接委托服务层查询用户详情。
        // 核心代码：调用查询服务
        return weiboService.getUser(userId);
    }

    // ===========================
    // 2. 微博内容模块
    // ===========================

    /**
     * 发布微博
     *
     * 实现逻辑：
     * 1. 获取用户ID与微博内容。
     * 2. 调用服务层完成发布流程。
     *
     * @param userId 发布用户ID
     * @param body 微博内容
     * @return 微博ID
     * @throws JsonProcessingException JSON 序列化异常
     */
    @PostMapping("/weibo")
    @SimpleRateLimit(qps = "${rate.limit.weibo-create-qps:" + RateLimitConstants.WEIBO_CREATE_QPS + "}",
            type = SimpleRateLimit.LimitType.DISTRIBUTED)
    public String createPost(@RequestHeader("userId") String userId, @RequestBody WeiboPost body) throws JsonProcessingException {
        // 实现思路：
        // 1. 直接委托服务层完成微博发布。
        // 核心代码：调用发布服务
        return weiboService.createPost(userId, body);
    }

    /**
     * 获取最新动态列表
     *
     * 实现逻辑：
     * 1. 调用服务层读取时间轴列表。
     *
     * @return 最新微博列表
     */
    @GetMapping("/weibo/list")
    public List<WeiboPost> listPosts() {
        // 实现思路：
        // 1. 直接委托服务层读取列表。
        // 核心代码：调用列表服务
        return weiboService.listLatestPosts();
    }

    // ===========================
    // 3. 互动与榜单
    // ===========================

    /**
     * 点赞微博
     *
     * 实现逻辑：
     * 1. 接收用户ID与微博ID。
     * 2. 调用服务层完成点赞逻辑。
     *
     * @param userId 点赞用户ID
     * @param postId 微博ID
     * @return 是否成功
     */
    @PostMapping("/weibo/{postId}/like")
    public Boolean likePost(@RequestHeader("userId") String userId,
                            @PathVariable String postId) {
        // 实现思路：
        // 1. 直接委托服务层完成点赞。
        // 核心代码：调用点赞服务
        return weiboService.likePost(userId, postId);
    }

    /**
     * 获取全站热搜排行榜
     *
     * 实现逻辑：
     * 1. 调用服务层读取热搜榜列表。
     *
     * @return 热搜榜列表
     */
    @GetMapping("/weibo/rank")
    public List<WeiboPost> getHotRank() {
        // 实现思路：
        // 1. 直接委托服务层获取排行榜。
        // 核心代码：调用排行榜服务
        return weiboService.getHotRank();
    }

    // ===========================
    // 4. 系统统计
    // ===========================

    /**
     * 获取全站 UV
     *
     * 实现逻辑：
     * 1. 调用服务层读取 UV 计数器。
     *
     * @return 全站 UV 数
     */
    @GetMapping("/system/uv")
    public Integer getTotalUV() {
        // 实现思路：
        // 1. 直接委托服务层获取 UV。
        // 核心代码：调用 UV 查询服务
        return weiboService.getTotalUV();
    }
}
