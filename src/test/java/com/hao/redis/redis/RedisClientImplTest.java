package com.hao.redis.redis;

import com.hao.redis.integration.redis.RedisClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RedisClient 实现测试
 *
 * 类职责：
 * 验证 RedisClient 封装的常用命令行为是否正确。
 *
 * 测试目的：
 * 1. 覆盖字符串、哈希、列表、集合、有序集合与通用命令。
 * 2. 验证删除与过期逻辑正确性。
 *
 * 设计思路：
 * - 使用随机前缀隔离测试数据。
 * - 按数据结构分组验证核心命令。
 *
 * 为什么需要该类：
 * RedisClient 是基础封装，任何错误都会影响业务层使用。
 *
 * 核心实现思路：
 * - 每个测试用例覆盖一类数据结构。
 * - 使用断言校验返回值与数据一致性。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Slf4j
class RedisClientImplTest {

    @Autowired
    private RedisClient<String> redisClient;

    private String prefix;

    /**
     * 测试前置初始化
     *
     * 实现逻辑：
     * 1. 生成随机前缀隔离测试数据。
     */
    @BeforeEach
    void setUp() {
        // 实现思路：
        // 1. 生成随机前缀并记录日志。
        prefix = "test:redisclient:" + UUID.randomUUID() + ":";
        log.info("测试开始|Test_start,prefix={}", prefix);
    }

    /**
     * 测试后置清理
     *
     * 实现逻辑：
     * 1. 删除当前前缀下的所有测试数据。
     */
    @AfterEach
    void cleanUp() {
        // 实现思路：
        // 1. 删除当前前缀下的所有键。
        Set<String> keys = redisClient.keys(prefix + "*");
        if (!keys.isEmpty()) {
            redisClient.del(keys.toArray(new String[0]));
            log.info("清理测试数据|Cleanup_test_data,deleted={},prefix={}", keys.size(), prefix);
        } else {
            log.info("无测试数据需清理|No_test_data_to_cleanup,prefix={}", prefix);
        }
    }

    /**
     * 生成带前缀的测试Key
     *
     * 实现逻辑：
     * 1. 拼接统一前缀与业务后缀。
     *
     * @param name 后缀名称
     * @return 组合后的 Key
     */
    private String k(String name) {
        // 实现思路：
        // 1. 返回带前缀的完整键。
        return prefix + name;
    }

    /**
     * 字符串命令验证
     *
     * 实现逻辑：
     * 1. 覆盖写入、读取、自增、自减与删除等命令。
     * 2. 校验返回结果与数据一致性。
     */
    @Test
    @DisplayName("字符串命令全量")
    void testStringOps() {
        // 实现思路：
        // 1. 执行字符串相关命令并断言结果。
        log.info("字符串命令验证|String_ops_verify");
        redisClient.set(k("s1"), "v1");
        redisClient.setex(k("s2"), 20, "v2");
        assertTrue(redisClient.setnx(k("s3"), "v3"));
        assertFalse(redisClient.setnx(k("s3"), "v3b"));

        redisClient.mset(Map.of(k("m1"), "a", k("m2"), "b"));
        assertEquals(Arrays.asList("v1", "v2", "a", "b"), redisClient.mget(k("s1"), k("s2"), k("m1"), k("m2")));

        assertEquals("v1", redisClient.getSet(k("s1"), "v1_new"));
        assertEquals("v1_new", redisClient.get(k("s1")));
        assertTrue(redisClient.exists(k("s2")));

        assertEquals(1L, redisClient.incr(k("counter")));
        assertEquals(6L, redisClient.incrBy(k("counter"), 5));
        assertEquals(4L, redisClient.decrBy(k("counter"), 2));
        assertEquals(3L, redisClient.decr(k("counter")));

        Double floatVal = redisClient.incrByFloat(k("floatCounter"), 1.5);
        assertEquals(1.5, floatVal);

        assertEquals(2L, redisClient.append(k("app"), "hi"));
        assertEquals(2L, redisClient.strlen(k("app")));

        Long removed = redisClient.del(k("s1"), k("s2"), k("s3"), k("m1"), k("m2"), k("counter"), k("floatCounter"), k("app"));
        assertEquals(8L, removed);
        log.info("字符串删除完成|String_delete_done,deleted={}", removed);
    }

    /**
     * 哈希命令验证
     *
     * 实现逻辑：
     * 1. 覆盖哈希写入、读取、计数与删除命令。
     * 2. 校验字段与值的正确性。
     */
    @Test
    @DisplayName("哈希命令全量")
    void testHashOps() {
        // 实现思路：
        // 1. 执行哈希相关命令并断言结果。
        log.info("哈希命令验证|Hash_ops_verify");
        String key = k("hash");
        redisClient.hset(key, "name", "tom");
        assertFalse(redisClient.hsetnx(key, "name", "other"));
        redisClient.hsetnx(key, "age", "18");
        redisClient.hmset(key, Map.of("city", "sz", "job", "dev"));

        assertEquals("tom", redisClient.hget(key, "name"));
        List<String> hmVals = redisClient.hmget(key, "name", "age", "city");
        assertEquals(Arrays.asList("tom", "18", "sz"), hmVals);
        assertTrue(redisClient.hexists(key, "job"));
        assertEquals(4L, redisClient.hlen(key));

        redisClient.hincrBy(key, "score", 10);
        redisClient.hincrByFloat(key, "price", 1.5);
        Map<String, String> all = redisClient.hgetAll(key);
        assertEquals("10", all.get("score"));
        assertEquals("1.5", all.get("price"));

        Set<String> keys = redisClient.hkeys(key);
        List<String> values = redisClient.hvals(key);
        assertTrue(keys.containsAll(Arrays.asList("name", "age", "city", "job", "score", "price")));
        assertEquals(values.size(), keys.size());

        assertEquals(1L, redisClient.hdel(key, "job"));
        log.info("哈希校验通过|Hash_verify_passed,fieldCount={}", redisClient.hlen(key));
    }

    /**
     * 列表命令验证
     *
     * 实现逻辑：
     * 1. 覆盖入队、出队、索引与裁剪等命令。
     * 2. 校验顺序与长度一致性。
     */
    @Test
    @DisplayName("列表命令全量")
    void testListOps() {
        // 实现思路：
        // 1. 执行列表相关命令并断言结果。
        log.info("列表命令验证|List_ops_verify");
        String key = k("list");
        String dst = k("list:dst");

        redisClient.lpush(key, "c", "b", "a"); // 列表内容：三个元素
        redisClient.rpush(key, "d");           // 列表内容：新增一个元素
        assertEquals(Arrays.asList("a", "b", "c", "d"), redisClient.lrange(key, 0, -1));

        assertEquals("a", redisClient.lindex(key, 0));
        redisClient.lset(key, 0, "a1");
        assertEquals("a1", redisClient.lindex(key, 0));

        redisClient.ltrim(key, 0, 2); // 保留前三项
        assertEquals(Arrays.asList("a1", "b", "c"), redisClient.lrange(key, 0, -1));

        redisClient.lrem(key, 1, "b");
        assertFalse(redisClient.lrange(key, 0, -1).contains("b"));

        redisClient.rpush(key, "tail");
        String moved = redisClient.rpoplpush(key, dst);
        assertEquals("tail", moved);
        assertEquals("tail", redisClient.lindex(dst, 0));

        // 准备阻塞弹出（已有值，不会等待）
        List<String> bl = redisClient.blpop(1, key);
        assertNotNull(bl);
        List<String> br = redisClient.brpop(1, dst);
        assertNotNull(br);

        assertEquals(redisClient.llen(key).longValue(), redisClient.lrange(key, 0, -1).size());
        log.info("列表长度校验通过|List_length_check_passed,llen={},rangeSize={}",
                redisClient.llen(key), redisClient.lrange(key, 0, -1).size());
    }

    /**
     * 无序集合命令验证
     *
     * 实现逻辑：
     * 1. 覆盖添加、移除、随机与交并差操作。
     * 2. 校验集合内容与数量一致性。
     */
    @Test
    @DisplayName("无序集合命令全量")
    void testSetOps() {
        // 实现思路：
        // 1. 执行集合相关命令并断言结果。
        log.info("无序集合命令验证|Set_ops_verify");
        String k1 = k("set1");
        String k2 = k("set2");
        String dst = k("set:dst");

        redisClient.sadd(k1, "a", "b", "c");
        redisClient.sadd(k2, "b", "c", "d");
        assertEquals(3L, redisClient.scard(k1));
        assertTrue(redisClient.sismember(k1, "a"));

        String rand = redisClient.srandmember(k1);
        assertNotNull(rand);
        List<String> rands = redisClient.srandmember(k1, 3);
        assertEquals(3, rands.size());

        Set<String> inter = redisClient.sinter(k1, k2);
        assertEquals(Set.of("b", "c"), inter);
        Set<String> union = redisClient.sunion(k1, k2);
        assertEquals(Set.of("a", "b", "c", "d"), union);
        Set<String> diff = redisClient.sdiff(k1, k2);
        assertEquals(Set.of("a"), diff);

        Long interStore = redisClient.sinterstore(dst + ":inter", k1, k2);
        assertEquals(2L, interStore);
        Long unionStore = redisClient.sunionstore(dst + ":union", k1, k2);
        assertEquals(4L, unionStore);
        Long diffStore = redisClient.sdiffstore(dst + ":diff", k1, k2);
        assertEquals(1L, diffStore);

        redisClient.srem(k1, "a");
        assertFalse(redisClient.sismember(k1, "a"));

        Set<String> popped = redisClient.spop(k2, 1);
        assertTrue(popped.size() <= 1);
        log.info("无序集合校验通过|Set_verify_passed,set1={},set2={}",
                redisClient.smembers(k1), redisClient.smembers(k2));
    }

    /**
     * 有序集合命令验证
     *
     * 实现逻辑：
     * 1. 覆盖新增、排名、范围查询与弹出操作。
     * 2. 校验顺序与计数一致性。
     */
    @Test
    @DisplayName("有序集合命令全量")
    void testZsetOps() {
        // 实现思路：
        // 1. 执行有序集合相关命令并断言结果。
        log.info("有序集合命令验证|Zset_ops_verify");
        String z1 = k("z1");
        String z2 = k("z2");
        String dst = k("z:dst");

        redisClient.zadd(z1, Map.of("u1", 10.0, "u2", 20.0));
        redisClient.zadd(z1, 15.0, "u3");
        redisClient.zadd(z2, Map.of("u2", 5.0, "u3", 7.0));

        assertEquals(Set.of("u1", "u3", "u2"), redisClient.zrange(z1, 0, -1));
        assertEquals(Set.of("u2", "u3", "u1"), redisClient.zrevrange(z1, 0, -1));
        assertEquals(Set.of("u1", "u3"), redisClient.zrangeByScore(z1, 0, 15));
        assertEquals(Set.of("u2", "u3"), redisClient.zrevrangeByScore(z1, 20, 12));

        assertEquals(Long.valueOf(0), redisClient.zrank(z1, "u1"));
        assertEquals(Long.valueOf(0), redisClient.zrevrank(z1, "u2"));

        assertEquals(Double.valueOf(10.0), redisClient.zscore(z1, "u1"));
        assertEquals(Double.valueOf(12.0), redisClient.zincrby(z1, 2.0, "u1"));
        assertEquals(3L, redisClient.zcard(z1));
        assertEquals(3L, redisClient.zcount(z1, 10, 20));

        assertEquals(1L, redisClient.zremrangeByScore(z1, 0, 12)); // 移除分数区间元素
        assertFalse(redisClient.zrange(z1, 0, -1).contains("u1"));
        assertEquals(1L, redisClient.zremrangeByRank(z1, 0, 0)); // 移除最小排名元素
        assertEquals(1L, redisClient.zcard(z1));

        redisClient.zrem(z1, "u2");
        assertEquals(0L, redisClient.zcard(z1));

        // 弹出与交并集存储测试
        redisClient.zadd(z1, Map.of("a", 1.0, "b", 2.0, "c", 3.0));
        Set<String> popMin = redisClient.zpopmin(z1, 1);
        assertTrue(popMin.contains("a"));
        Set<String> popMax = redisClient.zpopmax(z1, 1);
        assertTrue(popMax.contains("c"));

        Long interStore = redisClient.zinterstore(dst + ":inter", z1, z2);
        Long unionStore = redisClient.zunionstore(dst + ":union", z1, z2);
        assertEquals(0L, interStore);
        assertTrue(unionStore > 0);
        log.info("有序集合交并集完成|Zset_union_inter_done,inter={},union={}", interStore, unionStore);
    }

    /**
     * 过期与通用命令验证
     *
     * 实现逻辑：
     * 1. 覆盖过期、持久化与重命名操作。
     * 2. 校验TTL变化与重命名结果。
     */
    @Test
    @DisplayName("过期与通用命令全量")
    void testExpireAndCommon() {
        // 实现思路：
        // 1. 执行过期与通用命令并断言结果。
        log.info("通用与过期命令验证|Common_expire_ops_verify");
        String key = k("expire");
        redisClient.set(key, "1");
        assertTrue(redisClient.expire(key, 30));
        Long ttl1 = redisClient.ttl(key);
        assertNotNull(ttl1);
        assertTrue(ttl1 <= 30);

        long ts = Instant.now().getEpochSecond() + 60;
        assertTrue(redisClient.expireAt(key, ts));
        assertTrue(redisClient.ttl(key) <= 60);

        assertTrue(redisClient.persist(key));
        assertEquals(-1L, redisClient.ttl(key));

        String newKey = k("renamed");
        redisClient.rename(key, newKey);
        assertEquals("string", redisClient.type(newKey));

        String newKey2 = k("renamed2");
        assertTrue(redisClient.renamenx(newKey, newKey2));
        assertTrue(redisClient.keys(prefix + "*").contains(newKey2));
        log.info("通用与过期校验通过|Common_expire_verify_passed,keys={}", redisClient.keys(prefix + "*"));
    }
}
