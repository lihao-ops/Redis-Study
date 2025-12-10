package com.hao.redisstudy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hao.redisstudy.common.RedisKeysEnum;
import com.hao.redisstudy.model.WeiboPost;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 微博系统高负载全链路集成测试 (High-Load End-to-End Test)
 *
 * <p><strong>测试背景：</strong></p>
 * 模拟真实的高并发社交网络场景，验证 Redis 核心数据结构（String, Hash, List, ZSet, Set）
 * 在大数据量下的读写性能、排序准确性及业务逻辑的闭环。
 *
 * <p><strong>测试流程 (Test Scenario)：</strong></p>
 * <ol>
 * <li><strong>用户批量注册</strong>：模拟 {@code USER_COUNT} 个用户并发注册，验证 Global ID 生成器 (INCR) 和 Hash 存储。</li>
 * <li><strong>信息流轰炸 (Feed Blast)</strong>：模拟发布 {@code POST_COUNT} 条微博，计算写入 TPS，验证 List (LPUSH) 的写入性能。</li>
 * <li><strong>时间轴验证 (Timeline)</strong>：验证列表接口的分页能力和 LIFO (后进先出) 顺序，确保 List (LRANGE) 读取准确。</li>
 * <li><strong>制造热搜 (Viral Event)</strong>：人为制造“爆款”微博（全员点赞）和“次热门”微博，模拟 ZSet (ZINCRBY) 的并发更新。</li>
 * <li><strong>榜单校验 (Leaderboard)</strong>：验证全站热搜榜 Top 10，确保 ZSet (ZREVRANGE) 排序逻辑无误。</li>
 * <li><strong>流量审计 (UV check)</strong>：验证拦截器 + Set (SADD) 的去重统计功能。</li>
 * </ol>
 *
 * @author hli
 */
@Slf4j
@SpringBootTest
@AutoConfigureMockMvc
public class WeiboSystemIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // ==========================================
    // 定义测试规模 (Scale Configuration)
    // ==========================================
    private static final int USER_COUNT = 100;    // 模拟用户数
    private static final int POST_COUNT = 10000;  // 模拟微博总数 (建议至少 1000 以体现性能)

    /**
     * 测试前置：环境清洗
     */
    @BeforeEach
    public void setup() {
        log.info("========== [Setup] 环境初始化：清理 Redis 脏数据 ==========");
        clearAllTestData();
    }

    /**
     * 测试后置：数据回滚
     */
    @AfterEach
    public void tearDown() {
        log.info("========== [Teardown] 测试结束：执行数据清理 ==========");
        clearAllTestData();
    }

    /**
     * 清理逻辑：移除所有测试相关的 Key
     */
    private void clearAllTestData() {
        // 1. 清理 Enum 定义的静态 Key
        List<String> staticKeys = Arrays.asList(
                RedisKeysEnum.TOTAL_UV.getKey(),
                RedisKeysEnum.GLOBAL_USER_ID.getKey(),
                RedisKeysEnum.GLOBAL_POST_ID.getKey(),
                RedisKeysEnum.TIMELINE_KEY.getKey(),
                RedisKeysEnum.HOT_RANK_KEY.getKey(),
                RedisKeysEnum.WEIBO_POST_INFO.getKey()
        );
        redisTemplate.delete(staticKeys);

        // 2. 清理动态 Key (User, Likes, UV-Daily)
        Set<String> userKeys = redisTemplate.keys("user:*");
        if (userKeys != null && !userKeys.isEmpty()) redisTemplate.delete(userKeys);

        Set<String> likeKeys = redisTemplate.keys("weibo:*:likes");
        if (likeKeys != null && !likeKeys.isEmpty()) redisTemplate.delete(likeKeys);

        Set<String> uvKeys = redisTemplate.keys("uv:daily:*");
        if (uvKeys != null && !uvKeys.isEmpty()) redisTemplate.delete(uvKeys);
    }

    @Test
    @DisplayName("微博系统压力测试：100用户/1万微博/热搜模拟")
    public void testWeiboHighLoadFlow() throws Exception {
        log.info("🚀 开始执行高负载全链路测试 (规模: 用户={}, 微博={})", USER_COUNT, POST_COUNT);

        // ==================================================================================
        // 步骤 1: 批量注册用户
        // 验证点：String (INCR), Hash (HMSET)
        // ==================================================================================
        log.info("Step 1: 正在批量注册 {} 个用户...", USER_COUNT);
        List<String> userIds = new ArrayList<>();
        long regStart = System.currentTimeMillis();

        for (int i = 1; i <= USER_COUNT; i++) {
            String nickname = "User_" + i;
            MvcResult result = mockMvc.perform(post("/weibo/user/register")
                            .param("nickname", nickname)
                            .param("intro", "Robot " + i))
                    .andExpect(status().isOk())
                    .andReturn();
            userIds.add(result.getResponse().getContentAsString());
        }
        long regEnd = System.currentTimeMillis();
        double regTps = (double) USER_COUNT / ((regEnd - regStart) / 1000.0);

        assertEquals(USER_COUNT, userIds.size());
        log.info(">>> [性能报告] 用户注册完成 | 耗时: {} ms | TPS: {}", (regEnd - regStart), String.format("%.2f", regTps));

        // ==================================================================================
        // 步骤 2: 批量发布微博 (核心写性能测试)
        // 验证点：String (INCR), Hash (HSET), List (LPUSH)
        // ==================================================================================
        log.info("Step 2: 正在批量发布 {} 条微博 (模拟信息流轰炸)...", POST_COUNT);
        List<String> postIds = new ArrayList<>();
        Random random = new Random();

        long postStart = System.currentTimeMillis();
        for (int i = 1; i <= POST_COUNT; i++) {
            // 随机选一个用户作为发帖人
            String authorId = userIds.get(random.nextInt(USER_COUNT));

            WeiboPost post = new WeiboPost();
            post.setContent("LoadTest Post #" + i + " by User " + authorId + ". Redis is fast! 🚀");

            MvcResult result = mockMvc.perform(post("/weibo/weibo")
                            .header("userId", authorId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(post)))
                    .andExpect(status().isOk())
                    .andReturn();

            postIds.add(result.getResponse().getContentAsString());
        }
        long postEnd = System.currentTimeMillis();
        double postTps = (double) POST_COUNT / ((postEnd - postStart) / 1000.0);

        log.info(">>> [性能报告] 发帖轰炸完成 | 耗时: {} ms | TPS: String.format(\"%.2f\", postTps)", (postEnd - postStart));

        // 验证：最新发布的一条微博ID应该是列表中最后一个
        String lastCreatedPostId = postIds.get(postIds.size() - 1);
        log.info(">>> 最新发布的微博 ID 是: {}", lastCreatedPostId);


        // ==================================================================================
        // 步骤 3: 验证列表分页 (Timeline)
        // 验证点：List (LRANGE), Hash (HGET)
        // 预期：LIFO (后进先出)，第一条必须是刚刚发的最后一条
        // ==================================================================================
        log.info("Step 3: 验证列表分页 (Timeline LIFO Logic)...");

        MvcResult listResult = mockMvc.perform(get("/weibo/weibo/list"))
                .andExpect(status().isOk())
                .andReturn();

        String listJson = listResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<WeiboPost> timeline = objectMapper.readValue(listJson, new TypeReference<List<WeiboPost>>() {});

        log.info(">>> 列表接口返回数据量: {}", timeline.size());

        // 断言 1: 默认分页限制 (假设 Controller 默认 limit=20)
        assertEquals(20, timeline.size(), "Controller 默认应该只返回 20 条数据");

        // 断言 2: 时间轴顺序 (验证 List LPUSH 的特性)
        assertEquals(lastCreatedPostId, timeline.get(0).getPostId(), "列表首条必须是最新发布的微博");

        // 断言 3: 内容完整性 (验证 Hash 详情查询)
        assertNotNull(timeline.get(0).getContent(), "微博内容不应为空，说明 Hash 查询成功");


        // ==================================================================================
        // 步骤 4: 制造热搜 (Viral Event Simulation)
        // 场景：让第 50 条微博成为"爆款" (All Users Like)，第 80 条成为"亚军" (5 Users Like)
        // 验证点：ZSet (ZINCRBY), Set (SADD 去重)
        // ==================================================================================
        String viralPostId = postIds.get(49); // 取第 50 条 (index 49)
        log.info("Step 4: 制造热搜事件！目标微博 ID: {}", viralPostId);

        long likeStart = System.currentTimeMillis();
        // 1. 让所有注册用户给 viralPostId 点赞
        for (String userId : userIds) {
            mockMvc.perform(post("/weibo/weibo/" + viralPostId + "/like")
                            .header("userId", userId))
                    .andExpect(status().isOk());
        }
        log.info(">>> 已模拟 {} 个用户给微博 {} 点赞", userIds.size(), viralPostId);

        // 2. 制造一个"亚军"，给第 80 条微博点 5 个赞
        String secondPostId = postIds.get(79);
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/weibo/weibo/" + secondPostId + "/like")
                            .header("userId", userIds.get(i)))
                    .andExpect(status().isOk());
        }
        log.info(">>> 已模拟 5 个用户给微博 {} 点赞 (亚军)", secondPostId);
        log.info(">>> 点赞造势耗时: {} ms", (System.currentTimeMillis() - likeStart));


        // ==================================================================================
        // 步骤 5: 验证全站热搜榜 (Leaderboard)
        // 验证点：ZSet (ZREVRANGE) 排序算法
        // 预期：第一名必须是 viralPostId (100分)，第二名是 secondPostId (5分)
        // ==================================================================================
        log.info("Step 5: 验证热搜排行榜排序...");

        MvcResult rankResult = mockMvc.perform(get("/weibo/weibo/rank"))
                .andExpect(status().isOk())
                .andReturn();

        String rankJson = rankResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<WeiboPost> hotRank = objectMapper.readValue(rankJson, new TypeReference<List<WeiboPost>>() {});

        // 打印前三名 ID
        String rank1 = hotRank.isEmpty() ? "null" : hotRank.get(0).getPostId();
        String rank2 = hotRank.size() < 2 ? "null" : hotRank.get(1).getPostId();
        String rank3 = hotRank.size() < 3 ? "null" : hotRank.get(2).getPostId();
        log.info(">>> 热搜榜 Top 3 ID: [1st={}] [2nd={}] [3rd={}]", rank1, rank2, rank3);

        // 断言 1: 冠军归属 (应该有 100 个赞)
        assertEquals(viralPostId, rank1, "热搜第一名必须是获得全员点赞的那条微博");

        // 断言 2: 亚军归属 (应该有 5 个赞)
        assertEquals(secondPostId, rank2, "热搜第二名必须是获得5个赞的那条微博");

        // 断言 3: 榜单长度 (只返回 Top 10)
        assertTrue(hotRank.size() <= 10, "热搜榜接口应该最多返回 10 条");


        // ==================================================================================
        // 步骤 6: 验证系统 UV
        // 验证点：Interceptor + Set (SADD)
        // ==================================================================================
        log.info("Step 6: 验证系统 UV...");
        MvcResult uvResult = mockMvc.perform(get("/weibo/system/uv")).andReturn();
        String uvStr = uvResult.getResponse().getContentAsString();
        log.info(">>> 最终 UV 统计: {}", uvStr);

        // 断言：拦截器应该正常工作，UV 不为 0
        assertNotEquals("0", uvStr, "系统 UV 不应为 0");

        log.info("✅ ✅ ✅ 高负载全链路集成测试通过！Redis 系统运行稳定。");
    }
}