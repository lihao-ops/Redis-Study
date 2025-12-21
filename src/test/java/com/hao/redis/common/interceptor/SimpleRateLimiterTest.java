package com.hao.redis.common.interceptor;

import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guava RateLimiter 核心流程验证测试
 *
 * 测试目的：
 * 验证 Guava RateLimiter 的 "SmoothBursty" (平滑突发) 模式下的核心行为：
 * 1. 惰性填充 (Resync)：闲置一段时间后，令牌桶会自动回填。
 * 2. 预消费 (Reserve)：允许突发流量透支，但代价由下一次请求承担。
 * 3. 欠债偿还 (Wait)：透支后，后续请求必须等待“还债”。
 *
 * 对应流程图节点：
 * - 阶段一：惰性填充 (Resync) -> testLazyRefill
 * - 阶段二：计算与记账 (Reserve/Debt) -> testBurstyTrafficAndDebt
 * - 阶段三：执行与阻塞 (Sleep) -> testBlockingWait
 */
@Slf4j
public class SimpleRateLimiterTest {

    /**
     * 验证阶段一：惰性填充 (Resync)
     *
     * 流程图对应：
     * D{"当前时间 > nextFreeTicketMicros ?"} -- "是" --> E["触发 resync"] --> F["storedPermits增加"]
     *
     * 场景模拟：
     * 1. 创建 QPS=1 的限流器。
     * 2. 休眠 2 秒，让桶内积累令牌（惰性填充）。
     * 3. 瞬间请求 2 个令牌，应该立即通过（因为 storedPermits 足够）。
     */
    @Test
    @DisplayName("验证惰性填充机制: 闲置后令牌自动积累")
    public void testLazyRefill() throws InterruptedException {
        // [A] 开始: acquire 10 permits (这里模拟 acquire 1)
        // QPS = 1, 桶容量默认 maxBurstSeconds = 1.0 (即最多存 1 秒的量)
        RateLimiter limiter = RateLimiter.create(1.0);

        log.info("步骤1: 预热阶段，先消费掉初始令牌|Step1_warmup_consume_initial_permits");
        limiter.acquire(1); 

        log.info("步骤2: 休眠2秒，触发惰性填充(Resync)|Step2_sleep_2s_trigger_resync");
        // [D] 当前时间 > nextFreeTicketMicros ? -> 是(很久没人用)
        // [E] 触发 resync 方法
        // [F] 生成新令牌: storedPermits增加 (恢复到 maxPermits=1.0)
        // [G] 更新 nextFreeTicketMicros = 当前时间
        TimeUnit.SECONDS.sleep(2);

        log.info("步骤3: 突发请求，验证是否立即通过|Step3_burst_request_verify_instant_pass");
        Stopwatch stopwatch = Stopwatch.createStarted();
        
        // [B] synchronized mutex
        // [C] 调用 reserve
        // [H] 跳过填充 (因为刚刚 resync 过)
        // [I] 调用 reserveEarliestAvailable
        // [J] 计算本次需等待时间 waitMicros = 0 (因为 nextFreeTicketMicros <= now)
        // [K] 桶内令牌 storedPermits 够吗? -> 够用 (storedPermits=1.0, request=1)
        // [L] 消耗 storedPermits
        // [O] nextFreeTicketMicros 不变或微调
        // [P] 解锁 mutex
        // [Q] waitMicros > 0 ? -> 否
        // [S] 立即返回 0.0
        double waitTime = limiter.acquire(1);
        
        long elapsedMs = stopwatch.elapsed(TimeUnit.MILLISECONDS);
        log.info("请求耗时: {} ms, 理论等待: {} s|Request_cost_{}_ms_theoretical_wait_{}_s", elapsedMs, waitTime);

        // 验证：因为有 storedPermits，所以不需要等待（waitTime ≈ 0）
        assertTrue(waitTime < 0.1, "惰性填充失败：闲置后获取令牌依然需要等待");
    }

    /**
     * 验证阶段二：透支与欠债 (Reserve & Debt)
     *
     * 流程图对应：
     * K{"storedPermits 够吗?"} -- "不够" --> M["透支"] --> N["推后 nextFreeTicketMicros (欠新债)"]
     *
     * 场景模拟：
     * 1. QPS=1。
     * 2. 瞬间请求 10 个令牌（远超当前 storedPermits）。
     * 3. 预期：本次请求立即通过（透支），但下一次请求必须等待 10 秒（还债）。
     */
    @Test
    @DisplayName("验证透支机制: 本次透支，下次还债")
    public void testBurstyTrafficAndDebt() {
        RateLimiter limiter = RateLimiter.create(1.0); // QPS = 1

        log.info("步骤1: 突发请求10个令牌(透支)|Step1_burst_request_10_permits_overdraft");
        // [A] 开始: acquire 10 permits
        // [B] synchronized mutex
        // [C] 调用 reserve
        // ... (省略 resync) ...
        // [I] 调用 reserveEarliestAvailable
        // [J] 计算本次需等待时间 waitMicros = 0 (假设当前无积压)
        // [K] 桶内令牌 storedPermits 够吗? -> 不够 (storedPermits=1.0, request=10)
        // [M] 透支: 消耗全部 storedPermits, 剩余缺口(9个)算作 freshPermits
        // [N] 推后下一次可用时间 (欠新债): nextFreeTicketMicros += 9 * 1秒 = 当前时间 + 9秒
        // [P] 解锁 mutex
        // [Q] waitMicros > 0 ? -> 否 (本次不等待)
        // [S] 立即返回 0.0
        double waitTime1 = limiter.acquire(10);
        log.info("第一次请求(10个)等待时间: {} s|First_request_wait_time_{}_s", waitTime1);
        
        assertTrue(waitTime1 < 0.1, "透支机制失效：突发请求被阻塞了，而不是预消费");

        log.info("步骤2: 再次请求1个令牌(还债)|Step2_request_1_permit_pay_debt");
        Stopwatch stopwatch = Stopwatch.createStarted();
        
        // [A] 开始: acquire 1 permit
        // [B] synchronized mutex
        // [C] 调用 reserve
        // [D] 当前时间 > nextFreeTicketMicros ? -> 否 (因为步骤1把 nextFreeTicketMicros 推后了9秒)
        // [H] 跳过填充
        // [I] 调用 reserveEarliestAvailable
        // [J] 计算本次需等待时间 (还旧债): waitMicros = nextFreeTicketMicros - now ≈ 9秒
        // [K] 桶内令牌 storedPermits 够吗? -> 不够 (storedPermits=0)
        // [M] 透支...
        // [N] 推后下一次可用时间...
        // [P] 解锁 mutex
        // [Q] waitMicros > 0 ? -> 是 (需要还债)
        // [R] 休眠 (阻塞当前线程): sleepMicrosUninterruptibly(9秒)
        // [S] 返回等待时间
        double waitTime2 = limiter.acquire(1);
        long elapsedMs = stopwatch.elapsed(TimeUnit.MILLISECONDS);
        
        log.info("第二次请求(1个)等待时间: {} s, 实际阻塞: {} ms|Second_request_wait_{}_s_actual_block_{}_ms", 
                waitTime2, elapsedMs);

        // 验证：必须等待约 10 秒（允许少量误差，这里因为第一次请求消耗了1个storedPermits，透支了9个，所以等待约9秒，加上第二次请求本身的1秒间隔，总共约10秒）
        // 修正注释：acquire(10) -> 消耗1个stored，透支9个fresh -> nextFreeTicketMicros推后9秒
        // 下一次 acquire(1) -> 等待9秒 + 自身消耗1秒 = 10秒？
        // 实际上 Guava 的逻辑是：本次请求只需等待“上次欠的债”。
        // 上次欠了9秒，所以本次 acquire(1) 进来时，nextFreeTicketMicros 在9秒后。
        // 所以 waitMicros ≈ 9秒。
        // 但这里断言写的是 9.5~10.5，可能是因为 create(1.0) 初始 storedPermits 为 0？
        // 不，create(1.0) 初始 storedPermits 为 0，但会立即 resync 到 1.0 (如果 maxBurstSeconds=1.0)。
        // 让我们看实际运行结果。通常 acquire(10) 会导致 nextFreeTicketMicros += 10秒。
        // 第一次 acquire(10) -> wait=0, nextFreeTicketMicros += 10s.
        // 第二次 acquire(1) -> wait=10s.
        assertTrue(waitTime2 >= 9.5 && waitTime2 <= 10.5, 
                "还债机制失效：第二次请求没有等待预期的时长");
    }

    /**
     * 验证阶段三：阻塞等待 (Sleep)
     *
     * 流程图对应：
     * Q{"waitMicros > 0 ?"} -- "是" --> R["休眠 (阻塞当前线程)"]
     *
     * 场景模拟：
     * 1. QPS=5 (每 200ms 一个令牌)。
     * 2. 连续请求，验证是否被平滑地阻塞。
     */
    @Test
    @DisplayName("验证阻塞机制: 平滑限流")
    public void testBlockingWait() {
        double qps = 5.0;
        RateLimiter limiter = RateLimiter.create(qps); // 200ms 一个

        log.info("步骤1: 预热|Step1_warmup");
        limiter.acquire(1);

        log.info("步骤2: 连续请求，验证间隔|Step2_continuous_requests_verify_interval");
        for (int i = 0; i < 3; i++) {
            Stopwatch stopwatch = Stopwatch.createStarted();
            
            // [A] 开始: acquire 1 permit
            // ...
            // [J] 计算等待时间 waitMicros ≈ 200ms (因为 QPS=5)
            // ...
            // [Q] waitMicros > 0 ? -> 是
            // [R] 休眠 (阻塞当前线程)
            double waitTime = limiter.acquire(1);
            long elapsedMs = stopwatch.elapsed(TimeUnit.MILLISECONDS);
            
            log.info("第{}次请求等待: {} s, 实际耗时: {} ms|Request_{}_wait_{}_s_cost_{}_ms", 
                    i+1, waitTime, elapsedMs, i+1, waitTime, elapsedMs);

            // 验证：每次请求应该被阻塞约 200ms (1/5秒)
            // 允许 20ms 的系统调度误差
            assertTrue(elapsedMs >= 180 && elapsedMs <= 250, 
                    "平滑限流失效：请求间隔不符合 QPS 设定");
        }
    }
}
