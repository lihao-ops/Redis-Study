package com.hao.redis.integration.lock;

/**
 * 分布式锁服务接口
 * <p>
 * 职责：
 * 作为获取分布式锁实例的工厂。
 * <p>
 * 设计目的：
 * 统一分布式锁的创建入口，便于未来切换底层实现（如从 Redis 切换到 Zookeeper）。
 */
public interface DistributedLockService {

    /**
     * 根据给定的名称获取一个分布式锁实例。
     *
     * @param name 锁的唯一名称（对应 Redis Key）
     * @return 分布式锁实例
     */
    DistributedLock getLock(String name);
}
