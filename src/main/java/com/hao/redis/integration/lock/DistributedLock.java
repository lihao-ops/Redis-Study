package com.hao.redis.integration.lock;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁对象接口
 * <p>
 * 职责：
 * 定义了分布式锁的基本行为，模仿 java.util.concurrent.locks.Lock 接口。
 * <p>
 * 设计目的：
 * 为上层业务提供统一的、与具体实现无关的锁操作 API。
 */
public interface DistributedLock {

    /**
     * 阻塞式加锁，自动续期（看门狗）。
     * 如果锁不可用，则当前线程将被禁用以进行线程调度，并且处于休眠状态，直到获得锁。
     */
    void lock();

    /**
     * 尝试非阻塞式加锁，自动续期（看门狗）。
     *
     * @return {@code true} 如果成功获取锁, {@code false} 如果锁已被其他线程持有。
     */
    boolean tryLock();

    /**
     * 尝试在指定时间内加锁，自动续期（看门狗）。
     *
     * @param time 等待时间
     * @param unit 时间单位
     * @return {@code true} 如果成功获取锁, {@code false} 如果在等待时间内未获取到锁。
     * @throws InterruptedException 如果当前线程在获取锁之前被中断
     */
    boolean tryLock(long time, TimeUnit unit) throws InterruptedException;

    /**
     * 解锁。
     * 只有持有锁的线程才能成功解锁。
     */
    void unlock();
}
