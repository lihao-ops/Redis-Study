package com.hao.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.hao.redis.common.enums.RedisKeysEnum;
import com.hao.redis.common.util.JsonUtil;
import com.hao.redis.dal.model.WeiboPost;

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
 * 微博系统高负载全链路集成测试
 *
 * 类职责：
 * 在真实 HTTP 链路下验证微博业务全流程与 Redis 数据结构行为。
 *
 * 测试目的：
 * 1. 验证用户注册、发帖、时间轴与热搜榜流程闭环。
 * 2. 验证 Redis String/Hash/List/ZSet/Set 在高负载下的正确性与性能。
 * 3. 验证 UV 统计与拦截器逻辑是否生效。
 *
 * 设计思路：
 * - 使用 MockMvc 模拟真实 HTTP 请求链路。
 * - 分阶段执行注册、发帖、排行、UV 验证，并输出性能指标。
 *
 * 为什么需要该类：
 * 全链路集成测试可覆盖多组件协作风险，避免单元测试遗漏关键路径。
 *
 * 核心实现思路：
 * - 按阶段驱动请求并统计耗时与成功率。
 * - 核验关键数据结构的顺序与正确性。
 */
@Slf4j
@SpringBootTest
@AutoConfigureMockMvc
public class WeiboSystemIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // ==========================================
    // 定义测试规模
    // ==========================================
    private static final int USER_COUNT = 100;    // 模拟用户数
    private static final int POST_COUNT = 10000;  // 模拟微博总数 (建议至少 1000 以体现性能)

    /**
     * 测试前置：环境清洗
     *
     * 实现逻辑：
     * 1. 清理测试相关的 Redis 数据。
     */
    @BeforeEach
    public void setup() {
        // 实现思路：
        // 1. 测试前清理历史数据，确保结果可重复。
        log.info("测试前置清理|Test_setup_cleanup");
        clearAllTestData();
    }

    /**
     * 测试后置：数据回滚
     *
     * 实现逻辑：
     * 1. 清理测试期间生成的数据。
     */
    @AfterEach
    public void tearDown() {
        // 实现思路：
        // 1. 测试后清理数据，避免污染后续测试。
        log.info("测试后置清理|Test_teardown_cleanup");
        clearAllTestData();
    }

    /**
     * 清理测试数据
     *
     * 实现逻辑：
     * 1. 删除静态 Key。
     * 2. 删除动态 Key 前缀集合。
     */
    private void clearAllTestData() {
        // 实现思路：
        // 1. 清理静态键与动态键，保证测试数据隔离。
        // 1. 清理枚举定义的静态键
        List<String> staticKeys = Arrays.asList(
                RedisKeysEnum.TOTAL_UV.getKey(),
                RedisKeysEnum.GLOBAL_USER_ID.getKey(),
                RedisKeysEnum.GLOBAL_POST_ID.getKey(),
                RedisKeysEnum.TIMELINE_KEY.getKey(),
                RedisKeysEnum.HOT_RANK_KEY.getKey(),
                RedisKeysEnum.WEIBO_POST_INFO.getKey()
        );
        redisTemplate.delete(staticKeys);

        // 2. 清理动态键（用户、点赞、每日访客）
        Set<String> userKeys = redisTemplate.keys("user:*");
        if (userKeys != null && !userKeys.isEmpty()) redisTemplate.delete(userKeys);

        Set<String> likeKeys = redisTemplate.keys("weibo:*:likes");
        if (likeKeys != null && !likeKeys.isEmpty()) redisTemplate.delete(likeKeys);

        Set<String> uvKeys = redisTemplate.keys("uv:daily:*");
        if (uvKeys != null && !uvKeys.isEmpty()) redisTemplate.delete(uvKeys);
    }

    /**
     * 微博系统全链路高负载压测
     *
     * 实现逻辑：
     * 1. 批量注册用户并统计耗时。
     * 2. 批量发帖并验证时间轴顺序。
     * 3. 制造热搜并校验排行榜排序。
     * 4. 校验 UV 统计结果。
     *
     * @throws Exception 执行异常
     */
    @Test
    @DisplayName("微博系统压力测试：100用户/1万微博/热搜模拟")
    public void testWeiboHighLoadFlow() throws Exception {
        // 实现思路：
        // 1. 按阶段执行全链路压测。
        // 2. 记录耗时与结果指标。
        log.info("全链路压测开始|End_to_end_stress_start,userCount={},postCount={}", USER_COUNT, POST_COUNT);

        // ==================================================================================
        // 步骤1：批量注册用户
        // 验证点：字符串发号器与哈希存储
        // ==================================================================================
        log.info("步骤1_批量注册用户|Step1_batch_register_users,count={}", USER_COUNT);
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
        log.info("性能报告_注册完成|Register_report,costMs={},tps={}", (regEnd - regStart), String.format("%.2f", regTps));

        // ==================================================================================
        // 步骤2：批量发布微博
        // 验证点：发号器、详情哈希、时间轴列表
        // ==================================================================================
        log.info("步骤2_批量发布微博|Step2_batch_publish_posts,count={}", POST_COUNT);
        List<String> postIds = new ArrayList<>();
        Random random = new Random();

        long postStart = System.currentTimeMillis();
        for (int i = 1; i <= POST_COUNT; i++) {
            // 随机选一个用户作为发帖人
            String authorId = userIds.get(random.nextInt(USER_COUNT));

            WeiboPost post = new WeiboPost();
            post.setContent("压测微博#" + i + "_用户" + authorId + "_Redis高性能");

            MvcResult result = mockMvc.perform(post("/weibo/weibo")
                            .header("userId", authorId)
                            .contentType(MediaType.APPLICATION_JSON)
                            // 优化：使用 JsonUtil
                            .content(JsonUtil.toJson(post)))
                    .andExpect(status().isOk())
                    .andReturn();
            postIds.add(result.getResponse().getContentAsString());
        }
        long postEnd = System.currentTimeMillis();
        double postTps = (double) POST_COUNT / ((postEnd - postStart) / 1000.0);

        log.info("性能报告_发帖完成|Post_publish_report,costMs={},tps={}", (postEnd - postStart), String.format("%.2f", postTps));

        // 验证：最新发布的一条微博编号应该是列表中最后一个
        String lastCreatedPostId = postIds.get(postIds.size() - 1);
        log.info("最新微博ID|Latest_post_id,id={}", lastCreatedPostId);


        // ==================================================================================
        // 步骤3：验证时间轴分页
        // 验证点：列表读取与详情哈希
        // 预期：后进先出，第一条必须是最新发布
        // ==================================================================================
        log.info("步骤3_验证时间轴分页|Step3_verify_timeline");

        MvcResult listResult = mockMvc.perform(get("/weibo/weibo/list"))
                .andExpect(status().isOk())
                .andReturn();

        String listJson = listResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 优化：使用 JsonUtil
        List<WeiboPost> timeline = JsonUtil.toType(listJson, new TypeReference<List<WeiboPost>>() {});

        log.info("时间轴返回数量|Timeline_size,count={}", timeline.size());

        // 断言1：默认分页限制（假设控制层默认限制值为20）
        assertEquals(20, timeline.size(), "控制层默认应该只返回20条数据");

        // 断言2：时间轴顺序（验证列表后进先出特性）
        assertEquals(lastCreatedPostId, timeline.get(0).getPostId(), "列表首条必须是最新发布的微博");

        // 断言3：内容完整性（验证详情哈希查询）
        assertNotNull(timeline.get(0).getContent(), "微博内容不应为空，说明哈希查询成功");


        // ==================================================================================
        // 步骤4：制造热搜事件
        // 场景：第50条成为爆款，第80条成为亚军
        // 验证点：有序集合加分与去重集合
        // ==================================================================================
        String viralPostId = postIds.get(49); // 取第50条
        log.info("步骤4_制造热搜事件|Step4_create_viral_event,postId={}", viralPostId);

        long likeStart = System.currentTimeMillis();
        // 1. 让所有注册用户给爆款微博点赞
        for (String userId : userIds) {
            mockMvc.perform(post("/weibo/weibo/" + viralPostId + "/like")
                            .header("userId", userId))
                    .andExpect(status().isOk());
        }
        log.info("爆款点赞完成|Viral_like_done,userCount={},postId={}", userIds.size(), viralPostId);

        // 2. 制造亚军，给第80条微博点5个赞
        String secondPostId = postIds.get(79);
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/weibo/weibo/" + secondPostId + "/like")
                            .header("userId", userIds.get(i)))
                    .andExpect(status().isOk());
        }
        log.info("亚军点赞完成|Runner_up_like_done,postId={},likeCount={}", secondPostId, 5);
        log.info("点赞耗时|Like_duration,costMs={}", (System.currentTimeMillis() - likeStart));


        // ==================================================================================
        // 步骤5：验证热搜排行榜排序
        // 验证点：有序集合降序排序
        // 预期：第一名为爆款微博，第二名为亚军微博
        // ==================================================================================
        log.info("步骤5_验证热搜排序|Step5_verify_rank");

        MvcResult rankResult = mockMvc.perform(get("/weibo/weibo/rank"))
                .andExpect(status().isOk())
                .andReturn();

        String rankJson = rankResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 优化：使用 JsonUtil
        List<WeiboPost> hotRank = JsonUtil.toType(rankJson, new TypeReference<List<WeiboPost>>() {});

        // 打印前三名编号
        String rank1 = hotRank.isEmpty() ? "null" : hotRank.get(0).getPostId();
        String rank2 = hotRank.size() < 2 ? "null" : hotRank.get(1).getPostId();
        String rank3 = hotRank.size() < 3 ? "null" : hotRank.get(2).getPostId();
        log.info("热搜榜前三|Hot_rank_top3,first={},second={},third={}", rank1, rank2, rank3);

        // 断言1：冠军归属（应该有100个赞）
        assertEquals(viralPostId, rank1, "热搜第一名必须是获得全员点赞的那条微博");

        // 断言2：亚军归属（应该有5个赞）
        assertEquals(secondPostId, rank2, "热搜第二名必须是获得5个赞的那条微博");

        // 断言3：榜单长度（仅返回前10条）
        assertTrue(hotRank.size() <= 10, "热搜榜接口应该最多返回10条");


        // ==================================================================================
        // 步骤6：验证系统访客统计
        // 验证点：拦截器与去重集合
        // ==================================================================================
        log.info("步骤6_验证UV统计|Step6_verify_uv");
        MvcResult uvResult = mockMvc.perform(get("/weibo/system/uv")).andReturn();
        String uvStr = uvResult.getResponse().getContentAsString();
        log.info("最终UV统计|Final_uv_count,uv={}", uvStr);

        // 断言：拦截器应该正常工作，访客数不为0
        assertNotEquals("0", uvStr, "系统访客数不应为0");

        log.info("压测通过|Stress_test_passed");
    }
}
