package com.hao.redis.common.util;

import com.hao.redis.integration.redis.RedisClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 布隆过滤器综合测试类
 * <p>
 * 测试目的：
 * 1. 验证布隆过滤器的基本写入与查询功能（准确性）。
 * 2. 评估在大数据量下的实际误判率（假阳性率）。
 * 3. 演示当发生假阳性（误判）时，业务系统如何通过兜底机制解决该问题。
 * 4. 验证布隆过滤器的重建流程（解决误判率过高问题的终极手段）。
 * <p>
 * 设计思路：
 * - 使用真实 Redis 环境进行集成测试。
 * - 通过大量随机数据模拟生产环境流量。
 * - 模拟“误判”场景，验证回源逻辑的正确性。
 */
@Slf4j
@SpringBootTest
public class BloomFilterTest {

    @Autowired
    private BloomFilterUtil bloomFilterUtil;

    @Autowired
    private RedisClient<String> redisClient;

    // 测试用的业务分类 Key
    private static final String TEST_CATEGORY = "test_unit";
    private static final String BLOOM_KEY = "bloom:filter:" + TEST_CATEGORY;

    /**
     * 清理测试数据
     * <p>
     * 实现逻辑：
     * 1. 每次测试结束后删除 Redis 中的 BitMap，避免污染下次测试。
     */
    @AfterEach
    public void tearDown() {
        // 实现思路：
        // 1. 删除测试生成的 Key。
        redisClient.del(BLOOM_KEY);
        log.info("测试数据清理完成|Test_data_cleaned,key={}", BLOOM_KEY);
    }

    /**
     * 测试基本功能：写入与存在性判断
     * <p>
     * 测试逻辑：
     * 1. 写入一组已知数据。
     * 2. 验证这些数据必须返回 true（布隆过滤器不允许假阴性）。
     * 3. 验证未写入的数据大概率返回 false。
     */
    @Test
    @DisplayName("基本功能验证：写入与查询")
    public void testBasicOperations() {
        // 实现思路：
        // 1. 定义测试数据集。
        // 2. 写入布隆过滤器。
        // 3. 断言查询结果。
        String userId1 = "1001";
        String userId2 = "1002";

        // 写入数据
        bloomFilterUtil.add(TEST_CATEGORY, userId1);
        bloomFilterUtil.add(TEST_CATEGORY, userId2);
        log.info("已写入测试数据|Data_written,ids={},{}", userId1, userId2);

        // 验证存在的元素（必须为 true）
        boolean exists1 = bloomFilterUtil.mightContain(TEST_CATEGORY, userId1);
        boolean exists2 = bloomFilterUtil.mightContain(TEST_CATEGORY, userId2);
        
        Assertions.assertTrue(exists1, "已写入的元素必须被检测到");
        Assertions.assertTrue(exists2, "已写入的元素必须被检测到");

        // 验证不存在的元素（大概率 false）
        boolean notExists = bloomFilterUtil.mightContain(TEST_CATEGORY, "9999");
        // 注意：这里不能绝对断言为 false，因为存在极低概率的误判，但在小数据量下通常为 false
        if (notExists) {
            log.warn("发生偶发性误判_属于正常现象|False_positive_occurred,id=9999");
        } else {
            log.info("未写入元素校验通过|Non_existent_element_check_passed");
        }
    }

    /**
     * 压力测试：评估误判率（False Positive Rate）
     * <p>
     * 测试逻辑：
     * 1. 插入 10,000 条随机数据。
     * 2. 查询另外 10,000 条完全不同的随机数据。
     * 3. 统计被误判为“存在”的数量。
     * 4. 计算误判率并验证是否在可接受范围内（如 < 1%）。
     */
    @Test
    @DisplayName("压力测试：误判率评估")
    public void testFalsePositiveRate() {
        // 实现思路：
        // 1. 生成样本数据。
        // 2. 批量写入。
        // 3. 批量查询不存在的数据并统计误判。
        
        int sampleSize = 10000;
        List<String> insertedData = new ArrayList<>(sampleSize);
        
        // 1. 插入数据
        long start = System.currentTimeMillis();
        for (int i = 0; i < sampleSize; i++) {
            String uuid = UUID.randomUUID().toString();
            insertedData.add(uuid);
            bloomFilterUtil.add(TEST_CATEGORY, uuid);
        }
        long cost = System.currentTimeMillis() - start;
        log.info("数据插入完成|Data_insertion_completed,size={},costMs={}", sampleSize, cost);

        // 2. 测试误判
        int falsePositives = 0;
        for (int i = 0; i < sampleSize; i++) {
            // 生成肯定不存在的数据
            String nonExistentData = UUID.randomUUID().toString() + "_new";
            if (bloomFilterUtil.mightContain(TEST_CATEGORY, nonExistentData)) {
                falsePositives++;
            }
        }

        // 3. 计算误判率
        double rate = (double) falsePositives / sampleSize;
        log.info("误判率测试结果|FPP_test_result,sampleSize={},falsePositives={},rate={}", 
                sampleSize, falsePositives, String.format("%.4f", rate));

        // 验证误判率是否符合预期 (当前配置下，1万条数据误判率应极低，预期 < 0.01)
        Assertions.assertTrue(rate < 0.01, "误判率过高，需调整位数组大小或Hash函数数量");
    }

    /**
     * 场景演示：解决假阳性问题（业务兜底机制）
     * <p>
     * 核心思想：
     * 布隆过滤器只能作为“第一道防线”。当它判断“存在”时，业务代码必须查库（或查缓存）进行“二次确认”。
     * 只有当布隆过滤器判断“不存在”时，我们才能直接拒绝请求。
     * <p>
     * 测试逻辑：
     * 1. 模拟一个“误判”的场景（强制认为某 ID 在布隆过滤器中）。
     * 2. 执行业务查询逻辑。
     * 3. 验证业务逻辑最终返回 null（正确结果），而不是错误数据。
     */
    @Test
    @DisplayName("场景演示：假阳性业务兜底")
    public void testFalsePositiveHandling() {
        // 实现思路：
        // 1. 选定一个不存在的 ID。
        // 2. 手动污染 BitMap，强制让该 ID 在布隆过滤器中判定为 true（模拟误判）。
        // 3. 模拟业务查询流程：Bloom -> Cache/DB。
        // 4. 验证最终结果正确性。

        String nonExistentId = "user_8888";

        // 1. 确认该 ID 此时不在布隆过滤器中
        Assertions.assertFalse(bloomFilterUtil.mightContain(TEST_CATEGORY, nonExistentId));

        // 2. 【模拟误判】：手动将该 ID 对应的 BitMap 位全部置为 1
        // 注意：这里利用了 BloomFilterUtil 的内部逻辑，实际生产中是因为 Hash 冲突导致的
        // 为了测试稳定，我们直接调用 add 方法强制“污染”
        bloomFilterUtil.add(TEST_CATEGORY, nonExistentId);
        
        log.info("已模拟构造假阳性场景|Simulated_false_positive,id={}", nonExistentId);
        
        // 此时布隆过滤器会说“存在”
        boolean bloomCheck = bloomFilterUtil.mightContain(TEST_CATEGORY, nonExistentId);
        Assertions.assertTrue(bloomCheck, "前置条件：必须模拟出误判场景");

        // 3. 【业务兜底逻辑】：模拟 Service 层查询
        String result = mockBusinessQuery(nonExistentId);

        // 4. 验证结果：虽然布隆过滤器误判了，但业务返回必须是 null
        Assertions.assertNull(result, "业务层必须具备兜底机制，不能仅依赖布隆过滤器");
        
        log.info("假阳性兜底验证通过_系统健壮|False_positive_handling_passed");
    }

    /**
     * 模拟业务查询方法（Mock Service）
     * 
     * 实现逻辑：
     * 1. 先查布隆过滤器。
     * 2. 如果通过，再查真实数据源（这里模拟为空）。
     */
    private String mockBusinessQuery(String id) {
        // 1. 布隆过滤器拦截
        if (!bloomFilterUtil.mightContain(TEST_CATEGORY, id)) {
            log.info("请求被布隆过滤器拦截|Request_blocked_by_bloom_filter,id={}", id);
            return null;
        }

        log.warn("布隆过滤器放行_进入回源查询|Bloom_filter_passed_fallback_to_source,id={}", id);

        // 2. 回源查询（模拟数据库查不到数据）
        // 在真实场景中，这里会查 Redis 或 MySQL
        return queryDatabase(id);
    }

    private String queryDatabase(String id) {
        // 模拟数据库中不存在该 ID
        log.info("数据库查询为空|Database_query_empty,id={}", id);
        return null;
    }

    /**
     * 运维场景：布隆过滤器重建（解决误判率过高问题）
     * <p>
     * 场景描述：
     * 当数据量超过预期（Expected Insertions）时，误判率会显著上升。
     * 此时唯一的解决办法是：创建一个新的、更大的布隆过滤器，将所有历史数据重新写入，然后原子切换。
     * <p>
     * 测试逻辑：
     * 1. 模拟旧过滤器。
     * 2. 执行重建流程（清空 -> 重新加载）。
     * 3. 验证功能恢复。
     */
    @Test
    @DisplayName("运维场景：过滤器重建")
    public void testRebuildBloomFilter() {
        // 实现思路：
        // 1. 写入旧数据。
        // 2. 执行重建（删除 Key -> 重新写入）。
        // 3. 验证数据依然存在。
        
        String importantData = "vip_user_007";
        bloomFilterUtil.add(TEST_CATEGORY, importantData);
        
        // 模拟重建触发
        log.info("开始执行布隆过滤器重建|Start_rebuilding_bloom_filter");
        
        // 1. 删除旧 Key (在生产环境通常是计算新 Key，双写，然后切换，这里演示简化版：直接清空重来)
        redisClient.del(BLOOM_KEY);
        
        // 2. 验证此时数据“丢失”
        Assertions.assertFalse(bloomFilterUtil.mightContain(TEST_CATEGORY, importantData));
        
        // 3. 重新加载数据（模拟从数据库全量读取）
        List<String> allDataFromDb = List.of(importantData, "other_user_001");
        for (String data : allDataFromDb) {
            bloomFilterUtil.add(TEST_CATEGORY, data);
        }
        
        log.info("布隆过滤器重建完成|Bloom_filter_rebuild_done");
        
        // 4. 验证功能恢复
        Assertions.assertTrue(bloomFilterUtil.mightContain(TEST_CATEGORY, importantData), "重建后数据必须可查");
    }
}
