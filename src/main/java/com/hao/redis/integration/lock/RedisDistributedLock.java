package com.hao.redis.integration.lock;

import com.hao.redis.integration.redis.RedisClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的分布式锁实现（含看门狗）
 */
@Slf4j
public class RedisDistributedLock implements DistributedLock {

    private final String lockKey;
    private final RedisClient<String> redisClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final long lockWatchdogTimeout;

    private final ThreadLocal<String> threadLockValue = new ThreadLocal<>();
    private final ThreadLocal<Integer> threadLockCount = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<ScheduledFuture<?>> watchdogTask = new ThreadLocal<>();

    private static final ScheduledExecutorService WATCHDOG_EXECUTOR = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "RedisLockWatchdog");
                thread.setDaemon(true);
                return thread;
            }
    );

    public RedisDistributedLock(String lockKey, RedisClient<String> redisClient, StringRedisTemplate stringRedisTemplate, long lockWatchdogTimeout) {
        this.lockKey = lockKey;
        this.redisClient = redisClient;
        this.stringRedisTemplate = stringRedisTemplate;
        this.lockWatchdogTimeout = lockWatchdogTimeout;
    }

    @Override
    public void lock() {
        while (!tryLock()) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("加锁等待被中断|Lock_wait_interrupted, key={}", lockKey);
                return; // 中断后应退出循环
            }
        }
    }

    @Override
    public boolean tryLock() {
        if (threadLockCount.get() > 0) {
            threadLockCount.set(threadLockCount.get() + 1);
            log.debug("锁重入成功|Lock_reentrant_success, key={}, count={}", lockKey, threadLockCount.get());
            return true;
        }

        String lockValue = getLockValue();
        Boolean success = redisClient.tryLock(lockKey, lockValue, lockWatchdogTimeout, TimeUnit.MILLISECONDS);

        if (Boolean.TRUE.equals(success)) {
            threadLockValue.set(lockValue);
            threadLockCount.set(1);
            startWatchdog();
            return true;
        }
        return false;
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        long waitTime = unit.toMillis(time);

        while (System.currentTimeMillis() - startTime < waitTime) {
            if (tryLock()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    @Override
    public void unlock() {
        if (threadLockCount.get() == 0) {
            return;
        }

        threadLockCount.set(threadLockCount.get() - 1);

        if (threadLockCount.get() > 0) {
            log.debug("锁重入解锁_计数减一|Lock_reentrant_unlock, key={}, count={}", lockKey, threadLockCount.get());
            return;
        }

        try {
            stopWatchdog();
            
            String script = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                            "return redis.call('del', KEYS[1]) " +
                            "else return 0 end";
            
            stringRedisTemplate.execute(
                    new DefaultRedisScript<>(script, Long.class),
                    Collections.singletonList(lockKey),
                    threadLockValue.get()
            );
        } finally {
            threadLockValue.remove();
            threadLockCount.remove();
            watchdogTask.remove();
        }
    }

    private void startWatchdog() {
        long renewalInterval = lockWatchdogTimeout / 3;
        final String lockValue = threadLockValue.get();

        ScheduledFuture<?> future = WATCHDOG_EXECUTOR.scheduleAtFixedRate(() -> {
            try {
                String script = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                                "return redis.call('pexpire', KEYS[1], ARGV[2]) " +
                                "else return 0 end";
                
                Long result = stringRedisTemplate.execute(
                        new DefaultRedisScript<>(script, Long.class),
                        Collections.singletonList(lockKey),
                        lockValue,
                        String.valueOf(lockWatchdogTimeout)
                );

                if (Long.valueOf(1).equals(result)) {
                    log.debug("看门狗续期成功|Watchdog_renew_success, key={}", lockKey);
                } else {
                    log.warn("看门狗续期失败_停止任务|Watchdog_renew_fail_stopping, key={}", lockKey);
                    throw new IllegalStateException("Lock not held, stopping watchdog.");
                }
            } catch (Exception e) {
                log.error("看门狗续期异常|Watchdog_renew_error, key={}", lockKey, e);
                throw new RuntimeException(e);
            }
        }, renewalInterval, renewalInterval, TimeUnit.MILLISECONDS);
        
        watchdogTask.set(future);
    }

    private void stopWatchdog() {
        ScheduledFuture<?> future = watchdogTask.get();
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private String getLockValue() {
        return UUID.randomUUID().toString() + ":" + Thread.currentThread().getId();
    }
}
