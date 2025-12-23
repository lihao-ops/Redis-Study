package com.hao.redis.common.util;

import com.hao.redis.integration.redis.RedisClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 布隆过滤器工具类
 * <p>
 * 类职责：
 * 提供基于 Redis BitMap 的分布式布隆过滤器实现，用于快速判断元素是否存在。
 * <p>
 * 设计目的：
 * 1. 解决缓存穿透问题，拦截不存在的 Key。
 * 2. 提供高性能的去重判断能力。
 * <p>
 * 为什么需要该类：
 * 传统 Set 结构占用内存大，布隆过滤器以极小的空间换取高效的判断（允许少量误判）。
 * <p>
 * 核心实现思路：
 * - 使用多重 Hash 算法计算位偏移量。
 * - 映射到 Redis 的 BitMap 位数组中。
 * - 支持多业务场景（通过 category 区分）。
 */
@Slf4j
@Component
public class BloomFilterUtil {

    @Autowired
    private RedisClient<String> redisClient;

    // Redis Key 前缀
    private static final String BLOOM_FILTER_PREFIX = "bloom:filter:";

    /**
     * 布隆过滤器容量配置
     * <p>
     * 1. 位数组长度 (m): 2^24 = 16,777,216 bits (约 16Mb = 2MB 内存)
     * 2. Hash 函数个数 (k): 3 个
     * <p>
     * --- 容量与误判率计算公式 ---
     * 公式：n_max ≈ m / 9.58 (当期望误判率 p = 0.01 时)
     * <p>
     * 计算推导：
     * - 当前 m = 16,777,216
     * - 最大支撑数据量 n_max ≈ 16,777,216 / 9.58 ≈ 1,751,275 (约 175 万)
     * <p>
     * 结论：
     * - 在插入 175 万条数据以内，误判率 < 1%。
     * - 若插入 1 万条数据（如单元测试），误判率 ≈ 5.8e-9 (接近 0)。
     * - 若业务数据量超过 175 万，需增大 BIT_SIZE (如 1 << 28)。
     */
    // 2^24，约 1600 万位，占用 Redis 2MB 内存
    private static final long BIT_SIZE = 1 << 24; 
    
    // Hash 函数数量，k = (m/n) * ln2，当 m/n=10 时，k≈7；这里为了性能取 3
    private static final int HASH_COUNT = 3;

    /**
     * 添加元素到布隆过滤器
     *
     * @param category 业务分类（如 user, post）
     * @param value    待添加元素
     */
    public void add(String category, String value) {
        if (value == null || category == null) {
            return;
        }
        
        String key = BLOOM_FILTER_PREFIX + category;
        
        // 1. 计算 Hash 位置
        long[] offsets = getOffsets(value);
        
        // 2. 设置 Redis BitMap
        for (long offset : offsets) {
            redisClient.setBit(key, offset, true);
        }
    }

    /**
     * 判断元素是否存在
     *
     * @param category 业务分类（如 user, post）
     * @param value    待判断元素
     * @return true 可能存在，false 一定不存在
     */
    public boolean mightContain(String category, String value) {
        if (value == null || category == null) {
            return false;
        }

        String key = BLOOM_FILTER_PREFIX + category;

        // 1. 计算 Hash 位置
        long[] offsets = getOffsets(value);

        // 2. 检查 Redis BitMap
        for (long offset : offsets) {
            if (!Boolean.TRUE.equals(redisClient.getBit(key, offset))) {
                return false; // 只要有一位为 0，则一定不存在
            }
        }

        return true; // 所有位都为 1，可能存在
    }

    /**
     * 计算元素在 BitMap 中的偏移量
     * <p>
     * 这里简化实现，模拟 Guava 的多重 Hash 策略。
     * 实际生产中应使用更严谨的 MurmurHash 等算法生成多个索引。
     * 为了演示方便，这里使用简单的多次 Hash 模拟。
     *
     * @param value 元素
     * @return 偏移量数组
     */
    private long[] getOffsets(String value) {
        // 模拟 3 个 Hash 函数
        long hash1 = value.hashCode();
        long hash2 = hash1 * 31 + value.length();
        long hash3 = hash1 * 17 + value.charAt(0);
        
        long[] offsets = new long[HASH_COUNT];
        
        offsets[0] = Math.abs(hash1 % BIT_SIZE);
        offsets[1] = Math.abs(hash2 % BIT_SIZE);
        offsets[2] = Math.abs(hash3 % BIT_SIZE);
        
        return offsets;
    }
}
