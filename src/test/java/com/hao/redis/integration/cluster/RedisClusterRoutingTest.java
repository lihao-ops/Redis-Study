package com.hao.redis.integration.cluster;

import com.hao.redis.common.util.RedisSlotUtil;
import com.hao.redis.integration.redis.RedisClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Redis 集群路由与拓扑缓存测试
 * <p>
 * 测试目的：
 * 1. 验证 `RedisClusterTopologyCache` 是否能正确加载并缓存集群的拓扑信息。
 * 2. 验证 `RedisSlotUtil` 计算的 Slot 是否与实际路由一致。
 * 3. 验证 `RedisRouteMonitorAspect` 是否能正确监控并打印 Key 的路由路径。
 */
@Slf4j
@SpringBootTest
public class RedisClusterRoutingTest {

    @Autowired
    private RedisClusterTopologyCache topologyCache;

    @Autowired
    private RedisClient<String> redisClient;

    // 用于存储测试中创建的 Key，以便最后清理
    private final List<String> testKeys = new ArrayList<>();

    @BeforeEach
    public void setUp() {
        testKeys.clear();
    }

    @AfterEach
    public void tearDown() {
        if (!testKeys.isEmpty()) {
            redisClient.del(testKeys.toArray(new String[0]));
            log.info("测试数据清理完成|Test_data_cleaned, keys={}", testKeys);
        }
    }

    /**
     * 场景一：打印集群拓扑信息
     * <p>
     * 验证 `RedisClusterTopologyCache` 是否正常工作。
     */
    @Test
    @DisplayName("打印集群拓扑：验证拓扑缓存加载")
    public void testPrintClusterTopology() {
        log.info(">>> 开始打印当前 Redis 集群拓扑信息...");

        // 通过反向查询所有 Slot 来构建拓扑图
        Map<String, List<Integer>> nodeSlotsMap = new HashMap<>();
        for (int i = 0; i < RedisSlotUtil.CLUSTER_SLOTS; i++) {
            String node = topologyCache.getNodeBySlot(i);
            if (node != null) {
                nodeSlotsMap.computeIfAbsent(node, k -> new ArrayList<>()).add(i);
            }
        }

        Assertions.assertFalse(nodeSlotsMap.isEmpty(), "拓扑缓存加载失败，缓存为空！");

        log.info("====================== Redis Cluster Topology ======================");
        nodeSlotsMap.forEach((node, slots) -> {
            // 将离散的 slot 列表转换为连续的范围，便于阅读
            String ranges = formatSlotRanges(slots);
            log.info("Node: {} | Slots: {} (Total: {})", node, ranges, slots.size());
        });
        log.info("====================================================================");
    }

    /**
     * 场景二：验证 Key 路由与单节点读写
     * <p>
     * 验证 `RedisSlotUtil` 和 `RedisRouteMonitorAspect` 是否协同工作正常。
     */
    @Test
    @DisplayName("路由验证：写入并读取跨节点的 Key")
    public void testKeyRoutingAndVerification() {
        log.info(">>> 开始路由验证测试...");

        // 1. 智能查找分别落在不同节点上的 Key
        Map<String, String> nodeKeyMap = findKeysForDifferentNodes();
        Assertions.assertFalse(nodeKeyMap.isEmpty(), "寻找跨节点 Key 失败，请检查集群状态！");

        log.info(">>> 已找到分布在不同节点上的 Key: {}", nodeKeyMap);

        // 2. 写入这些 Key，并观察控制台日志
        // `RedisRouteMonitorAspect` 会自动打印每个 Key 的路由信息
        log.info(">>> 开始写入数据，请观察下方 AOP 日志...");
        nodeKeyMap.forEach((node, key) -> {
            String value = "value_for_" + node.replace(":", "_");
            redisClient.set(key, value, 60); // 设置 60 秒过期
            testKeys.add(key); // 记录下来，以便清理
        });
        log.info(">>> 数据写入完成。");

        // 3. 验证是否能从集群中正确读回数据
        // 虽然 Redis 客户端是集群感知的，能自动路由到正确节点，
        // 但成功读回数据，结合 AOP 日志，就能证明整个链路是通的。
        log.info(">>> 开始验证读取...");
        nodeKeyMap.forEach((node, key) -> {
            String expectedValue = "value_for_" + node.replace(":", "_");
            String actualValue = redisClient.get(key);
            log.info("读取 Key: {}, 期望值: {}, 实际值: {}", key, expectedValue, actualValue);
            Assertions.assertEquals(expectedValue, actualValue, "读取 Key [" + key + "] 的值不匹配！");
        });

        log.info(">>> 路由验证测试通过！");
    }

    /**
     * 辅助方法：智能查找分别落在不同节点上的 Key
     */
    private Map<String, String> findKeysForDifferentNodes() {
        Map<String, String> nodeKeyMap = new HashMap<>();
        Map<String, String> distinctNodes = new HashMap<>();

        // 先获取所有主节点
        for (int i = 0; i < RedisSlotUtil.CLUSTER_SLOTS; i++) {
            String node = topologyCache.getNodeBySlot(i);
            if (node != null) {
                distinctNodes.put(node, "");
            }
        }

        if (distinctNodes.isEmpty()) return Collections.emptyMap();

        int maxAttempts = 10000; // 设置最大尝试次数，防止死循环
        int attempts = 0;
        while (nodeKeyMap.size() < distinctNodes.size() && attempts < maxAttempts) {
            String key = UUID.randomUUID().toString();
            int slot = RedisSlotUtil.getSlot(key);
            String node = topologyCache.getNodeBySlot(slot);

            if (node != null && !nodeKeyMap.containsKey(node)) {
                nodeKeyMap.put(node, key);
            }
            attempts++;
        }
        return nodeKeyMap;
    }

    /**
     * 辅助方法：格式化 Slot 列表为范围字符串
     * 例如: [0, 1, 2, 5, 6, 10] -> "[0-2], [5-6], [10]"
     */
    private String formatSlotRanges(List<Integer> slots) {
        if (slots == null || slots.isEmpty()) {
            return "";
        }
        slots.sort(Integer::compareTo);

        List<String> ranges = new ArrayList<>();
        int start = slots.get(0);
        int end = slots.get(0);

        for (int i = 1; i < slots.size(); i++) {
            if (slots.get(i) == end + 1) {
                end = slots.get(i);
            } else {
                ranges.add(start == end ? String.valueOf(start) : start + "-" + end);
                start = slots.get(i);
                end = slots.get(i);
            }
        }
        ranges.add(start == end ? String.valueOf(start) : start + "-" + end);

        return ranges.stream().collect(Collectors.joining(", "));
    }
}
