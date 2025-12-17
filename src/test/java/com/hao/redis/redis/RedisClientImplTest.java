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

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Slf4j
class RedisClientImplTest {

    @Autowired
    private RedisClient<String> redisClient;

    private String prefix;

    @BeforeEach
    void setUp() {
        prefix = "test:redisclient:" + UUID.randomUUID() + ":";
        log.info("开始测试，用例前缀: {}", prefix);
    }

    @AfterEach
    void cleanUp() {
        Set<String> keys = redisClient.keys(prefix + "*");
        if (!keys.isEmpty()) {
            redisClient.del(keys.toArray(new String[0]));
            log.info("清理测试数据，删除 {} 个 key，前缀 {}", keys.size(), prefix);
        } else {
            log.info("无测试数据需要清理，前缀 {}", prefix);
        }
    }

    private String k(String name) {
        return prefix + name;
    }

    /** 验证字符串全部命令：set/setex/setnx/mset/mget/getset/exists/incr系列/decr系列/append/strlen/del，预期数据一致、计数正确。 */
    @Test
    @DisplayName("字符串命令全量")
    void testStringOps() {
        log.info("【字符串】验证 set/setex/setnx/mset/mget/getset/incr/decr/append/strlen/del 全流程");
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
        log.info("【字符串】删除完成，删除数量 {}", removed);
    }

    /** 验证哈希全部命令：hset/hsetnx/hget/hgetall/hmset/hmget/hkeys/hvals/hlen/hexists/hdel/hincrby/float，预期字段和值正确。 */
    @Test
    @DisplayName("哈希命令全量")
    void testHashOps() {
        log.info("【哈希】验证 HSET/HSETNX/HGET/HGETALL/HMSET/HMGET/HKEYS/HVALS/HLEN/HEXISTS/HDEL/HINCRBY/HINCRBYFLOAT");
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
        log.info("【哈希】字段和值检查通过，当前字段数 {}", redisClient.hlen(key));
    }

    /** 验证列表全部命令：push/pop、阻塞 pop、索引读写、trim、remove、长度等，预期顺序与结果符合。 */
    @Test
    @DisplayName("列表命令全量")
    void testListOps() {
        log.info("【列表】验证 LPUSH/RPUSH/POP/阻塞POP/索引/裁剪/移除/搬移/长度");
        String key = k("list");
        String dst = k("list:dst");

        redisClient.lpush(key, "c", "b", "a"); // list: a b c
        redisClient.rpush(key, "d");           // a b c d
        assertEquals(Arrays.asList("a", "b", "c", "d"), redisClient.lrange(key, 0, -1));

        assertEquals("a", redisClient.lindex(key, 0));
        redisClient.lset(key, 0, "a1");
        assertEquals("a1", redisClient.lindex(key, 0));

        redisClient.ltrim(key, 0, 2); // keep first 3
        assertEquals(Arrays.asList("a1", "b", "c"), redisClient.lrange(key, 0, -1));

        redisClient.lrem(key, 1, "b");
        assertFalse(redisClient.lrange(key, 0, -1).contains("b"));

        redisClient.rpush(key, "tail");
        String moved = redisClient.rpoplpush(key, dst);
        assertEquals("tail", moved);
        assertEquals("tail", redisClient.lindex(dst, 0));

        // prepare blocking pop (value already exists so不会等待)
        List<String> bl = redisClient.blpop(1, key);
        assertNotNull(bl);
        List<String> br = redisClient.brpop(1, dst);
        assertNotNull(br);

        assertEquals(redisClient.llen(key).longValue(), redisClient.lrange(key, 0, -1).size());
        log.info("【列表】长度校验通过，llen={} rangeSize={}", redisClient.llen(key), redisClient.lrange(key, 0, -1).size());
    }

    /** 验证无序集合全部命令：添加/移除/成员检测/随机取/交并差及存储，预期集合结果正确且数量匹配。 */
    @Test
    @DisplayName("无序集合命令全量")
    void testSetOps() {
        log.info("【Set】验证 SADD/SREM/SCARD/SISMEMBER/随机/SINTER/SUNION/SDIFF 及存储");
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
        log.info("【Set】交并差及随机操作通过，set1={}, set2={}", redisClient.smembers(k1), redisClient.smembers(k2));
    }

    /** 验证有序集合全部命令：新增、范围/分数查询、排名、增删、交并集存储、弹出等，预期顺序与计数正确。 */
    @Test
    @DisplayName("有序集合命令全量")
    void testZsetOps() {
        log.info("【ZSet】验证 ZADD/范围与分数查询/排名/增删/交并集存储/弹出");
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

        assertEquals(1L, redisClient.zremrangeByScore(z1, 0, 12)); // remove u1(12)
        assertFalse(redisClient.zrange(z1, 0, -1).contains("u1"));
        assertEquals(1L, redisClient.zremrangeByRank(z1, 0, 0)); // remove最小 u3
        assertEquals(1L, redisClient.zcard(z1));

        redisClient.zrem(z1, "u2");
        assertEquals(0L, redisClient.zcard(z1));

        // pop and store tests
        redisClient.zadd(z1, Map.of("a", 1.0, "b", 2.0, "c", 3.0));
        Set<String> popMin = redisClient.zpopmin(z1, 1);
        assertTrue(popMin.contains("a"));
        Set<String> popMax = redisClient.zpopmax(z1, 1);
        assertTrue(popMax.contains("c"));

        Long interStore = redisClient.zinterstore(dst + ":inter", z1, z2);
        Long unionStore = redisClient.zunionstore(dst + ":union", z1, z2);
        assertEquals(0L, interStore);
        assertTrue(unionStore > 0);
        log.info("【ZSet】交并集存储完成，inter={}, union={}", interStore, unionStore);
    }

    /** 验证过期与通用命令：expire/expireAt/persist/ttl/type/rename/renamenx/keys，预期 TTL 变化与重命名正确。 */
    @Test
    @DisplayName("过期与通用命令全量")
    void testExpireAndCommon() {
        log.info("【通用与过期】验证 expire/expireAt/persist/ttl/type/rename/renamenx/keys");
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
        log.info("【通用与过期】重命名与 TTL 校验通过，当前 keys={}", redisClient.keys(prefix + "*"));
    }
}
