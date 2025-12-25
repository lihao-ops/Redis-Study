package com.hao.redis.common.util;

import com.hao.redis.common.model.RedisLogicalData;
import com.hao.redis.dal.model.WeiboPost;
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
 * 2. 【方案一】验证互斥锁（Mutex）解决击穿（保证强一致性，但吞吐量下降）。
 * 3. 【方案二】验证逻辑过期解决击穿（保证高可用，但存在数据弱一致）。
 * 4. 【安全性】验证 Lua 脚本原子加锁的必要性。
 */
@Slf4j
@SpringBootTest
public class CacheBreakdownTest {

    @Autowired
    private CacheBreakdownUtil cacheBreakdownUtil;

    @Autowired
    private RedisClient<String> redisClient;
    
    @Autowired
    private RedisClientImpl redisClientImpl; // 用于获取底层 Template 执行 Lua

    private static final String KEY_PREFIX = "test:breakdown:";
    private static final String POST_ID = "1001";
    
    // 升级并发量：1000 个线程同时请求
    // 注意：这需要 Redis 连接池配置足够大 (max-active >= 1000 或等待队列足够长)
    private static final int THREAD_COUNT = 1000; 

    // 模拟数据库调用计数器
    private final AtomicInteger dbCallCount = new AtomicInteger(0);

    /**
     * 测试前清理
     * 防止上次异常中断残留数据影响本次测试
     */
    @BeforeEach
    public void setUp() {
        cleanRedisData();
        dbCallCount.set(0);
    }

    /**
     * 测试后清理
     * 确保不污染环境
     */
    @AfterEach
    public void tearDown() {
        cleanRedisData();
        dbCallCount.set(0);
    }

    private void cleanRedisData() {
        redisClient.del(KEY_PREFIX + POST_ID);
        redisClient.del("lock:mutex:" + POST_ID);
        redisClient.del("lock:mutex:risk:" + POST_ID); // 清理死锁测试的 Key
        redisClient.del("lock:logical:" + KEY_PREFIX + POST_ID);
        log.info("Redis 测试数据已清理|Redis_data_cleaned");
    }

    // ==========================================
    // 场景一：基准测试 - 复现缓存击穿
    // ==========================================
    @Test
    @DisplayName("基准测试：无保护状态下复现缓存击穿")
    public void testCacheBreakdown_NoProtection() throws InterruptedException {
        // 1. 确保缓存为空（模拟过期）
        redisClient.del(KEY_PREFIX + POST_ID);

        log.info(">>> 开始基准测试：缓存已失效，{} 个线程并发访问...", THREAD_COUNT);

        // 2. 发起并发请求
        executeConcurrentTask(id -> {
            // 模拟普通业务逻辑：查缓存 -> 没命中 -> 查库 -> 写缓存
            String key = KEY_PREFIX + id;
            String cache = redisClient.get(key);
            if (cache == null) {
                // 击穿点：所有线程都会走到这里
                return mockDbQuery(id);
            }
            return cache;
        });

        // 3. 验证结果
        log.info(">>> 基准测试结束，DB 调用次数: {}", dbCallCount.get());
        
        // 预期：绝大多数请求都打到了数据库
        // 在 1000 并发下，DB 调用通常会超过 800
        Assertions.assertTrue(dbCallCount.get() > 500, "未发生击穿？DB调用次数过少: " + dbCallCount.get());
    }

    // ==========================================
    // 场景二：互斥锁方案 (Mutex Lock)
    // ==========================================
    
    /**
     * 子场景 A：非原子加锁的风险演示
     * 模拟：SETNX 成功后，还没来得及 EXPIRE 就挂了 -> 死锁
     */
    @Test
    @DisplayName("互斥锁风险：非原子加锁导致死锁")
    public void testMutexLock_NonAtomic_DeadLock() {
        String lockKey = "lock:mutex:risk:" + POST_ID;
        
        // 1. 模拟线程 A 加锁成功
        Boolean success = redisClient.setnx(lockKey, "thread-A");
        Assertions.assertTrue(success);
        
        // 2. 模拟线程 A 在设置过期时间前崩溃（抛出异常）
        try {
            if (true) throw new RuntimeException("模拟服务器断电/进程崩溃");
            redisClient.expire(lockKey, 10); // 这行代码永远不会执行
        } catch (Exception e) {
            log.info(">>> 线程 A 崩溃，未设置过期时间");
        }

        // 3. 验证：锁没有过期时间，变成了“死锁”
        Long ttl = redisClient.ttl(lockKey);
        log.info(">>> 当前锁 TTL: {}", ttl);
        
        // Redis 中 -1 代表永不过期
        Assertions.assertEquals(-1L, ttl, "锁应该没有过期时间（死锁）");
        
        // 清理死锁 (由 tearDown 统一处理，这里也可以显式清理)
        redisClient.del(lockKey);
    }

    /**
     * 子场景 B：原子加锁 (Lua 脚本) 解决击穿
     */
    @Test
    @DisplayName("互斥锁方案：Lua原子加锁解决击穿")
    public void testMutexLock_Atomic_Solution() throws InterruptedException {
        // 1. 确保缓存为空
        redisClient.del(KEY_PREFIX + POST_ID);

        log.info(">>> 开始互斥锁测试：{} 个线程并发访问...", THREAD_COUNT);

        executeConcurrentTask(id -> {
            return queryWithMutex(id);
        });

        // 3. 验证结果
        log.info(">>> 互斥锁测试结束，DB 调用次数: {}", dbCallCount.get());

        // 预期：只有 1 个线程打到数据库（考虑到多节点并发，可能偶尔有 2-3 个，但绝不会是 1000）
        Assertions.assertTrue(dbCallCount.get() <= 10, "互斥锁失效？DB调用次数过多: " + dbCallCount.get());
    }

    // 互斥锁查询逻辑封装
    private String queryWithMutex(String id) {
        String key = KEY_PREFIX + id;
        String cache = redisClient.get(key);
        if (cache != null) return cache;

        // 缓存未命中，尝试获取互斥锁
        String lockKey = "lock:mutex:" + id;
        // 使用 Lua 脚本原子加锁 (SET lock 1 NX EX 10)
        boolean isLock = tryLockAtomic(lockKey, "1", 10);

        try {
            if (isLock) {
                // 拿到锁，查库
                String dbData = mockDbQuery(id);
                // 写缓存
                redisClient.set(key, dbData, 60);
                return dbData;
            } else {
                // 没拿到锁，休眠重试
                try { Thread.sleep(50); } catch (InterruptedException e) {}
                return queryWithMutex(id); // 递归重试
            }
        } finally {
            if (isLock) {
                redisClient.del(lockKey); // 释放锁
            }
        }
    }

    // ==========================================
    // 场景三：逻辑过期方案 (Logical Expiration)
    // ==========================================
    @Test
    @DisplayName("逻辑过期方案：异步重建解决击穿")
    public void testLogicalExpire_Solution() throws InterruptedException {
        // 1. 预热：写入一个“已过期”的数据
        WeiboPost oldPost = new WeiboPost();
        oldPost.setPostId(POST_ID);
        oldPost.setContent("Old Content");
        
        RedisLogicalData<WeiboPost> logicalData = new RedisLogicalData<>();
        logicalData.setData(oldPost);
        logicalData.setExpireTime(LocalDateTime.now().minusSeconds(10)); // 过期 10 秒
        redisClient.set(KEY_PREFIX + POST_ID, JsonUtil.toJson(logicalData));

        log.info(">>> 开始逻辑过期测试：预热完成，{} 个线程并发访问...", THREAD_COUNT);

        // 2. 发起并发请求
        executeConcurrentTask(id -> {
            // 使用工具类查询
            WeiboPost post = cacheBreakdownUtil.queryWithLogicalExpire(
                    KEY_PREFIX, 
                    id, 
                    WeiboPost.class, 
                    this::mockDbQueryObj, // 查库函数
                    30L
            );
            return post.getContent();
        });

        // 等待异步线程完成
        TimeUnit.SECONDS.sleep(1);

        // 3. 验证结果
        log.info(">>> 逻辑过期测试结束，DB 调用次数: {}", dbCallCount.get());

        // 预期 1：DB 调用次数应该很少（理想是 1 次，极端情况可能重复提交任务，但通常由锁控制）
        Assertions.assertTrue(dbCallCount.get() <= 5, "异步重建多次触发？DB调用: " + dbCallCount.get());
        
        // 预期 2：Redis 中的数据应该已更新
        String json = redisClient.get(KEY_PREFIX + POST_ID);
        RedisLogicalData<WeiboPost> newData = JsonUtil.toBean(json, RedisLogicalData.class, WeiboPost.class);
        Assertions.assertEquals("New Content", newData.getData().getContent(), "缓存未更新");
    }

    // ==========================================
    // 辅助方法
    // ==========================================

    /**
     * 统一并发执行器 (升级版：使用 CyclicBarrier)
     */
    private void executeConcurrentTask(java.util.function.Function<String, String> task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        // 使用 CyclicBarrier 实现真正的“瞬时并发”
        // 只有当所有线程都准备好（await）时，才会同时放行
        CyclicBarrier barrier = new CyclicBarrier(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            pool.submit(() -> {
                try {
                    // 等待所有线程就绪
                    barrier.await();
                    // 同时起跑
                    task.apply(POST_ID);
                } catch (Exception e) {
                    log.error("并发任务执行异常", e);
                }
            });
        }
        
        pool.shutdown();
        // 等待所有任务结束（最长等待 10 秒）
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    /**
     * 模拟耗时 DB 查询 (返回 String)
     */
    private String mockDbQuery(String id) {
        dbCallCount.incrementAndGet();
        try { TimeUnit.MILLISECONDS.sleep(200); } catch (InterruptedException e) {}
        return "NewValue_" + System.currentTimeMillis();
    }

    /**
     * 模拟耗时 DB 查询 (返回对象)
     */
    private WeiboPost mockDbQueryObj(String id) {
        dbCallCount.incrementAndGet();
        try { TimeUnit.MILLISECONDS.sleep(200); } catch (InterruptedException e) {}
        WeiboPost p = new WeiboPost();
        p.setPostId(id);
        p.setContent("New Content");
        return p;
    }

    /**
     * Lua 原子加锁实现
     */
    private boolean tryLockAtomic(String key, String value, int expireSeconds) {
        // Lua 脚本：如果 key 不存在则设置并设置过期时间，否则返回 0
        String script = "if redis.call('setnx', KEYS[1], ARGV[1]) == 1 then " +
                        "redis.call('expire', KEYS[1], ARGV[2]) " +
                        "return 1 " +
                        "else return 0 end";
        
        StringRedisTemplate template = redisClientImpl.getRedisTemplate();
        Long result = template.execute(
                new DefaultRedisScript<>(script, Long.class),
                Collections.singletonList(key),
                value,
                String.valueOf(expireSeconds)
        );
        
        return Long.valueOf(1).equals(result);
    }
}
