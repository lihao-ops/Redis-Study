package com.hao.redis.common.util;

import com.hao.redis.common.model.RedisLogicalData;
import com.hao.redis.dal.model.WeiboPost;
import com.hao.redis.integration.lock.RedisDistributedLock;
import com.hao.redis.integration.redis.RedisClient;
import com.hao.redis.integration.redis.RedisClientImpl;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 缓存击穿全场景对比测试 (高并发版)
 * <p>
 * 测试目的：
 * 1. 【基准测试】复现缓存击穿现象（DB 请求量飙升）。
 * 2. 【方案一】验证互斥锁（Mutex）解决击穿，并对比原生命令、Lua 及看门狗机制。
 * 3. 【方案二】验证逻辑过期解决击穿（保证高可用）。
 */
@Slf4j
@SpringBootTest
public class CacheBreakdownTest {

    @Autowired
    private CacheBreakdownUtil cacheBreakdownUtil;

    @Autowired
    private RedisClient<String> redisClient;

    private final AtomicInteger dbCallCount = new AtomicInteger(0);
    private static final String KEY_PREFIX = "test:breakdown:";
    private static final String POST_ID = "1001";
    private static final int THREAD_COUNT = 1000;

    @BeforeEach
    public void setUp() {
        cleanRedisData();
        dbCallCount.set(0);
    }

    @AfterEach
    public void tearDown() {
        cleanRedisData();
        dbCallCount.set(0);
    }

    private void cleanRedisData() {
        redisClient.del(KEY_PREFIX + POST_ID);
        redisClient.del("lock:mutex:" + POST_ID);
        redisClient.del("lock:mutex:native:" + POST_ID);
        redisClient.del("lock:mutex:risk:" + POST_ID);
        redisClient.del("lock:mutex:watchdog:" + POST_ID);
        redisClient.del("lock:lock:mutex:watchdog:" + POST_ID);
        redisClient.del("lock:logical:" + KEY_PREFIX + POST_ID);
        log.info("Redis 测试数据已清理|Redis_data_cleaned");
    }

    @Test
    @DisplayName("基准测试：无保护状态下复现缓存击穿")
    public void testCacheBreakdown_NoProtection() throws InterruptedException {
        redisClient.del(KEY_PREFIX + POST_ID);
        log.info(">>> 开始基准测试：缓存已失效，{} 个线程并发访问...", THREAD_COUNT);

        executeConcurrentTask(id -> {
            String key = KEY_PREFIX + id;
            String cache = redisClient.get(key);
            if (cache == null) {
                return mockDbQuery(id);
            }
            return cache;
        });

        log.info(">>> 基准测试结束，DB 调用次数: {}", dbCallCount.get());
        Assertions.assertTrue(dbCallCount.get() > 500, "未发生击穿？DB调用次数过少: " + dbCallCount.get());
    }

    @Test
    @DisplayName("互斥锁风险：非原子加锁导致死锁")
    public void testMutexLock_NonAtomic_DeadLock() {
        String lockKey = "lock:mutex:risk:" + POST_ID;
        
        Boolean success = redisClient.setnx(lockKey, "thread-A");
        Assertions.assertTrue(success);
        
        try {
            throw new RuntimeException("模拟服务器断电/进程崩溃");
        } catch (Exception e) {
            log.info(">>> 线程 A 崩溃，未设置过期时间");
        }

        Long ttl = redisClient.ttl(lockKey);
        log.info(">>> 当前锁 TTL: {}", ttl);
        Assertions.assertEquals(-1L, ttl, "锁应该没有过期时间（死锁）");
        
        redisClient.del(lockKey);
    }

    @Test
    @DisplayName("互斥锁方案(原生命令)：SET NX EX 解决击穿")
    public void testMutexLock_NativeCommand_Solution() throws InterruptedException {
        redisClient.del(KEY_PREFIX + POST_ID);
        log.info(">>> 开始互斥锁测试(原生命令)：{} 个线程并发访问...", THREAD_COUNT);

        executeConcurrentTask(this::queryWithMutex_Native);

        log.info(">>> 互斥锁测试(原生命令)结束，DB 调用次数: {}", dbCallCount.get());
        Assertions.assertTrue(dbCallCount.get() <= 10, "互斥锁失效？DB调用次数过多: " + dbCallCount.get());
    }

    @Test
    @DisplayName("互斥锁方案(Lua脚本)：解决击穿")
    public void testMutexLock_Lua_Solution() throws InterruptedException {
        redisClient.del(KEY_PREFIX + POST_ID);
        log.info(">>> 开始互斥锁测试(Lua脚本)：{} 个线程并发访问...", THREAD_COUNT);

        executeConcurrentTask(this::queryWithMutex_Lua);

        log.info(">>> 互斥锁测试(Lua脚本)结束，DB 调用次数: {}", dbCallCount.get());
        Assertions.assertTrue(dbCallCount.get() <= 10, "互斥锁失效？DB调用次数过多: " + dbCallCount.get());
    }

    @Test
    @DisplayName("互斥锁方案(看门狗)：自动续期解决长耗时业务")
    public void testMutexLock_Watchdog_Solution() throws Exception {
        String lockName = "mutex:watchdog:" + POST_ID;
        String fullLockKey = "lock:" + lockName;
        
        RedisDistributedLock lock = new RedisDistributedLock(
            fullLockKey, redisClient, ((RedisClientImpl) redisClient).getRedisTemplate(), 5000L
        );
        
        lock.lock();
        log.info(">>> 已加锁，初始 TTL {} 秒", redisClient.ttl(fullLockKey));
        
        try {
            log.info(">>> 看门狗测试：开始执行长耗时业务 (8秒)...");
            Thread.sleep(8000); 
            log.info(">>> 长耗时业务完成");
            
            Assertions.assertTrue(redisClient.exists(fullLockKey), "业务执行完毕，锁意外丢失！");
            
            Long ttl = redisClient.ttl(fullLockKey);
            log.info(">>> 业务结束后锁的 TTL: {} 秒", ttl);
            Assertions.assertTrue(ttl > 0 && ttl <= 5, "看门狗续期失败或锁已过期！");

        } finally {
            lock.unlock();
            log.info(">>> 解锁完成");
        }
        
        Assertions.assertFalse(redisClient.exists(fullLockKey), "解锁失败，锁依然存在");
    }

    @Test
    @DisplayName("逻辑过期方案：异步重建解决击穿")
    public void testLogicalExpire_Solution() throws InterruptedException {
        WeiboPost oldPost = new WeiboPost();
        oldPost.setPostId(POST_ID);
        oldPost.setContent("Old Content");
        
        RedisLogicalData<WeiboPost> logicalData = new RedisLogicalData<>();
        logicalData.setData(oldPost);
        logicalData.setExpireTime(LocalDateTime.now().minusSeconds(10));
        redisClient.set(KEY_PREFIX + POST_ID, JsonUtil.toJson(logicalData));

        log.info(">>> 开始逻辑过期测试：预热完成，{} 个线程并发访问...", THREAD_COUNT);

        executeConcurrentTask(id -> {
            WeiboPost post = cacheBreakdownUtil.queryWithLogicalExpire(
                    KEY_PREFIX, id, WeiboPost.class, this::mockDbQueryObj, 30L
            );
            return post.getContent();
        });

        TimeUnit.SECONDS.sleep(1);

        log.info(">>> 逻辑过期测试结束，DB 调用次数: {}", dbCallCount.get());
        Assertions.assertTrue(dbCallCount.get() <= 5, "异步重建多次触发？DB调用: " + dbCallCount.get());
        
        String json = redisClient.get(KEY_PREFIX + POST_ID);
        RedisLogicalData<WeiboPost> newData = JsonUtil.toBean(json, RedisLogicalData.class, WeiboPost.class);
        Assertions.assertEquals("New Content", newData.getData().getContent(), "缓存未更新");
    }

    private String queryWithMutex_Native(String id) {
        String key = KEY_PREFIX + id;
        String cache = redisClient.get(key);
        if (cache != null) return cache;

        String lockKey = "lock:mutex:native:" + id;
        String lockValue = UUID.randomUUID().toString();
        
        boolean isLock = redisClient.tryLock(lockKey, lockValue, 10, TimeUnit.SECONDS);

        try {
            if (isLock) {
                String dbData = mockDbQuery(id);
                redisClient.set(key, dbData, 60);
                return dbData;
            } else {
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return queryWithMutex_Native(id);
            }
        } finally {
            unlock(lockKey, lockValue);
        }
    }

    private String queryWithMutex_Lua(String id) {
        String key = KEY_PREFIX + id;
        String cache = redisClient.get(key);
        if (cache != null) return cache;

        String lockKey = "lock:mutex:" + id;
        String lockValue = UUID.randomUUID().toString();
        
        boolean isLock = tryLockWithLua(lockKey, lockValue, 10);

        try {
            if (isLock) {
                String dbData = mockDbQuery(id);
                redisClient.set(key, dbData, 60);
                return dbData;
            } else {
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return queryWithMutex_Lua(id);
            }
        } finally {
            if (isLock) {
                unlock(lockKey, lockValue);
            }
        }
    }

    private void executeConcurrentTask(java.util.function.Function<String, String> task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        try {
            CyclicBarrier barrier = new CyclicBarrier(THREAD_COUNT);

            for (int i = 0; i < THREAD_COUNT; i++) {
                pool.submit(() -> {
                    try {
                        barrier.await();
                        task.apply(POST_ID);
                    } catch (Exception e) {
                        log.error("并发任务执行异常", e);
                    }
                });
            }
            
            pool.shutdown();
            if (!pool.awaitTermination(20, TimeUnit.SECONDS)) {
                log.warn("线程池关闭超时_强制关闭|Pool_shutdown_timeout");
                pool.shutdownNow();
            }
        } finally {
            if (!pool.isTerminated()) {
                pool.shutdownNow();
            }
        }
    }

    private String mockDbQuery(@SuppressWarnings("unused") String id) {
        dbCallCount.incrementAndGet();
        try { TimeUnit.MILLISECONDS.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return "NewValue_" + System.currentTimeMillis();
    }

    private WeiboPost mockDbQueryObj(String id) {
        dbCallCount.incrementAndGet();
        try { TimeUnit.MILLISECONDS.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        WeiboPost p = new WeiboPost();
        p.setPostId(id);
        p.setContent("New Content");
        return p;
    }

    private boolean tryLockWithLua(String key, String value, int expireSeconds) {
        String script = "if redis.call('setnx', KEYS[1], ARGV[1]) == 1 then " +
                        "redis.call('expire', KEYS[1], ARGV[2]) " +
                        "return 1 " +
                        "else return 0 end";
        
        StringRedisTemplate template = ((RedisClientImpl) redisClient).getRedisTemplate();
        Long result = template.execute(
                new DefaultRedisScript<>(script, Long.class),
                Collections.singletonList(key),
                value,
                String.valueOf(expireSeconds)
        );
        
        return Long.valueOf(1).equals(result);
    }

    private void unlock(String key, String value) {
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "return redis.call('del', KEYS[1]) " +
                        "else return 0 end";
        
        StringRedisTemplate template = ((RedisClientImpl) redisClient).getRedisTemplate();
        template.execute(
                new DefaultRedisScript<>(script, Long.class),
                Collections.singletonList(key),
                value
        );
    }
}
