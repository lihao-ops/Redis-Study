package com.hao.redis.common.util;

import io.lettuce.core.codec.CRC16;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis 集群槽位计算工具类
 *
 * 类职责：
 * 提供 Redis Cluster 分片算法实现，计算 Key 所属的哈希槽 (Slot)。
 *
 * 设计目的：
 * 1. 封装 CRC16 算法与取模逻辑，确保与 Redis 服务端行为一致。
 * 2. 支持 Hash Tag ({...}) 解析，确保相关 Key 落入同一槽位。
 *
 * 核心算法：
 * Slot = CRC16(key) % 16384
 */
@Slf4j
public class RedisSlotUtil {

    /**
     * Redis 集群总槽位数
     */
    public static final int CLUSTER_SLOTS = 16384;

    private RedisSlotUtil() {
        // 工具类禁止实例化
    }

    /**
     * 计算 Key 对应的 Slot
     *
     * 实现逻辑：
     * 1. 处理 Hash Tag：如果 Key 包含 {...}，仅计算花括号内的部分。
     * 2. 使用 CRC16 算法计算校验和。
     * 3. 对 16384 取模。
     *
     * @param key Redis Key
     * @return Slot ID (0 - 16383)
     */
    public static int getSlot(String key) {
        if (key == null) {
            return 0;
        }

        // 1. 处理 Hash Tag (例如 "user:{1001}:info" -> 计算 "1001")
        int start = key.indexOf('{');
        if (start != -1) {
            int end = key.indexOf('}', start + 1);
            if (end != -1 && end > start + 1) {
                key = key.substring(start + 1, end);
            }
        }

        // 2. 计算 CRC16 并取模
        // Lettuce 提供了标准的 CRC16 实现，直接复用避免造轮子
        return CRC16.crc16(key.getBytes()) % CLUSTER_SLOTS;
    }
}
