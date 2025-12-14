package com.hao.redisstudy.test;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 虚拟线程 Redis 高并发秒杀压测
 * <p>
 * 使用 ThreadPoolConfig 中配置的 "virtualThreadExecutor"
 */
@Slf4j
@SpringBootTest
public class VirtualThreadSeckillTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // --- 核心：注入你在 ThreadPoolConfig 中定义的虚拟线程执行器 ---
    @Autowired
    @Qualifier("virtualThreadExecutor")
    private Executor virtualThreadExecutor;

    private DefaultRedisScript<Long> deductStockScript;

    // --- ⚔️ 压测参数配置 ⚔️ ---
    // 使用 HashTag {} 确保 key 落在一个 slot，但测试集群单点性能
    private static final String PRODUCT_KEY = "{seckill}:product:9999";
    // 初始库存
    private static final int INITIAL_STOCK = 50_000;
    // 总请求量 (模拟 20万 用户瞬间发起请求)
    private static final int TOTAL_REQUESTS = 200_000;

    @BeforeEach
    public void setup() {
        // 1. 定义 Lua 脚本 (防超卖核心逻辑)
        String scriptText =
                "if (redis.call('get', KEYS[1]) == false) then return -1 end; " + // 安全检查
                        "local stock = tonumber(redis.call('get', KEYS[1])); " +
                        "if (stock > 0) then " +
                        "   redis.call('decr', KEYS[1]); " +
                        "   return 1; " + // 抢购成功
                        "else " +
                        "   return 0; " + // 库存不足
                        "end";

        deductStockScript = new DefaultRedisScript<>();
        deductStockScript.setScriptText(scriptText);
        deductStockScript.setResultType(Long.class);

        // 2. 初始化数据
        stringRedisTemplate.delete(PRODUCT_KEY);
        stringRedisTemplate.opsForValue().set(PRODUCT_KEY, String.valueOf(INITIAL_STOCK));

        // 3. 脚本预热 (Spring 会自动处理 SHA1，但先跑一次确保加载)
        try {
            stringRedisTemplate.execute(deductStockScript, Collections.singletonList(PRODUCT_KEY));
            // 预热扣减了一次，补回去
            stringRedisTemplate.opsForValue().increment(PRODUCT_KEY);
        } catch (Exception e) {
            log.warn("预热脚本失败，可能是首次加载", e);
        }

        log.info("🔥 [配置复用版] 虚拟线程压测准备就绪 | Key: {} | 库存: {} | 计划请求: {}",
                PRODUCT_KEY, INITIAL_STOCK, TOTAL_REQUESTS);
    }

    @Test
    public void benchmarkWithConfiguredExecutor() throws InterruptedException {
        // 同步控制
        CountDownLatch startLatch = new CountDownLatch(1);       // 发令枪
        CountDownLatch endLatch = new CountDownLatch(TOTAL_REQUESTS); // 结束信号

        // 计数器 (原子类，保证线程安全)
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        log.info("🚀 --- 正在从 ThreadPoolConfig 获取 virtualThreadExecutor 提交 {} 个任务 ---", TOTAL_REQUESTS);

        // 提交 20万 个任务到你的虚拟线程执行器
        // 注意：Executor 接口只有 execute 方法，没有 submit 返回 Future，所以必须用 latch 控制流程
        for (int i = 0; i < TOTAL_REQUESTS; i++) {
            virtualThreadExecutor.execute(() -> {
                try {
                    // 1. 所有虚拟线程在此等待，直到主线程发令
                    startLatch.await();

                    // 2. 执行 Redis Lua 脚本
                    Long result = stringRedisTemplate.execute(
                            deductStockScript,
                            Collections.singletonList(PRODUCT_KEY)
                    );

                    // 3. 统计结果
                    if (result != null && result == 1) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }

                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    log.error("请求异常", e);
                } finally {
                    // 4. 任务完成，倒数
                    endLatch.countDown();
                }
            });
        }

        // 给一点点时间让虚拟线程全部启动并阻塞在 await() 上
        // 虽然虚拟线程启动极快，但20万次循环提交也需要几十毫秒
        Thread.sleep(1000);

        log.info("🔫 砰！开抢！");
        long startTime = System.currentTimeMillis();

        // 开启发令枪，所有虚拟线程同时冲击
        startLatch.countDown();

        // 主线程等待所有任务结束
        endLatch.await();

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        // --- 📊 计算结果 ---
        double tps = (double) TOTAL_REQUESTS / duration * 1000;

        log.info("🛑 --- 压测结束 ---");
        log.info("耗时: {} ms (约 {} 秒)", duration, String.format("%.2f", duration / 1000.0));
        log.info("总请求数: {}", TOTAL_REQUESTS);
        log.info("成功抢购: {}", successCount.get());
        log.info("抢购失败: {}", failCount.get());
        log.info("异常数量: {}", errorCount.get());

        String finalStockStr = stringRedisTemplate.opsForValue().get(PRODUCT_KEY);
        log.info("Redis 最终库存: {}", finalStockStr);

        log.info("🏆 系统吞吐量 (TPS): {}", String.format("%.2f", tps));

        // 断言验证
        if (successCount.get() != INITIAL_STOCK) {
            throw new RuntimeException("❌ 卖出数量(" + successCount.get() + ")不等于初始库存！");
        }
        if (Integer.parseInt(finalStockStr) != 0) {
            throw new RuntimeException("❌ 最终库存(" + finalStockStr + ")不为0！");
        }
    }

    @AfterEach
    public void tearDown() {
        log.info("🧹 开始清理战场...");
        // 1. 删除业务 Key
        stringRedisTemplate.delete(PRODUCT_KEY);

        // 2. 尝试清理脚本 (SCRIPT FLUSH)
        try {
            stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
                connection.scriptingCommands().scriptFlush();
                return null;
            });
            log.info("✅ 脚本缓存清理命令已发送");
        } catch (Exception e) {
            log.warn("脚本清理警告: {}", e.getMessage());
        }
    }
}