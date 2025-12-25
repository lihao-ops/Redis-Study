# 分布式锁（看门狗版）接入与原理笔记

> **面试官视角**：
> 候选人你好，这篇笔记旨在帮你快速掌握我们项目中分布式锁的核心实现。
> 重点关注：**为什么需要看门狗？** **如何实现自动续期？** **如何保证原子性？**
> 掌握这些，你就能在面试中从容应对关于分布式锁的深挖。

## 一、 核心类与职责

我们采用的是 **Redis + Lua + 守护线程** 的方案，对标 Redisson 的核心功能。

| 类名 | 角色 | 职责 |
| :--- | :--- | :--- |
| `DistributedLockService` | **工厂 (Factory)** | 统一入口，负责创建锁实例，屏蔽底层依赖。 |
| `RedisDistributedLock` | **核心实现 (Core)** | 封装加锁、解锁、看门狗续期、可重入逻辑。 |
| `RedisClient` | **基础设施 (Infra)** | 提供底层的原子加锁命令 (`SET NX EX`)。 |

---

## 二、 完整调用链路 (Life Cycle)

### 1. 获取锁实例 (Factory Pattern)
业务代码不直接 `new` 锁对象，而是通过服务获取。

*   **入口**：`DistributedLockService.getLock("order:1001")`
*   **动作**：
    1.  拼接 Key 前缀 -> `lock:order:1001`。
    2.  注入 `RedisClient` 和 `StringRedisTemplate`。
    3.  注入配置好的 `lockWatchdogTimeout` (默认 30s)。
    4.  返回一个**新的** `RedisDistributedLock` 实例。

### 2. 加锁流程 (Locking)

*   **入口**：`lock.lock()` 或 `lock.tryLock()`
*   **步骤 1：可重入判断 (Reentrancy)**
    *   检查 `ThreadLocal<Integer> count`。
    *   若 `count > 0`，说明当前线程已持有锁 -> `count++` -> **return true**。
*   **步骤 2：原子加锁 (Atomic Operation)**
    *   生成唯一 `lockValue` (UUID + ThreadId)。
    *   调用 `RedisClient.tryLock()` -> 执行 `SET key value NX EX 30`。
    *   **关键点**：必须同时设置 NX (互斥) 和 EX (兜底过期)，防止死锁。
*   **步骤 3：启动看门狗 (Start Watchdog)**
    *   若加锁成功 -> `startWatchdog()`。
    *   创建一个后台定时任务 (`ScheduledFuture`)。
    *   **频率**：`lockWatchdogTimeout / 3` (默认每 10s 执行一次)。

### 3. 看门狗续期 (Watchdog Renewal)

*   **触发**：后台线程池 `WATCHDOG_EXECUTOR` 定时触发。
*   **逻辑 (Lua 脚本)**：
    ```lua
    -- 判断锁是不是我的 (防止续了别人的锁)
    if redis.call('get', KEYS[1]) == ARGV[1] then
        -- 是我的，重置过期时间为 30s
        return redis.call('pexpire', KEYS[1], ARGV[2])
    else
        -- 锁没了(可能业务跑太久被强制释放了)，停止续期
        return 0
    end
    ```
*   **异常处理**：如果续期失败（锁丢失），任务会自动抛出异常并终止，防止空转。

### 4. 解锁流程 (Unlocking)

*   **入口**：`lock.unlock()`
*   **步骤 1：可重入递减**
    *   `count--`。
    *   若 `count > 0` -> 仅递减，**不删锁** -> return。
*   **步骤 2：停止看门狗 (Stop Watchdog)**
    *   **关键点**：必须先停狗，再删锁。
    *   调用 `future.cancel(true)` 终止后台续期任务。
*   **步骤 3：原子删除 (Atomic Delete)**
    *   执行 Lua 脚本：
    ```lua
    -- 只有锁值匹配(是我的锁)才删除
    if redis.call('get', KEYS[1]) == ARGV[1] then
        return redis.call('del', KEYS[1])
    else
        return 0
    end
    ```
*   **步骤 4：清理上下文**
    *   `ThreadLocal.remove()` 清理计数器和锁值，防止内存泄漏。

---

## 三、 面试高频 Q&A

**Q1: 为什么需要看门狗？直接设一个长一点的过期时间不行吗？**
> **A**: 不行。
> 1.  **设短了**：业务没跑完锁就失效了，导致并发安全问题。
> 2.  **设长了**：万一服务宕机，锁要很久才能自动释放，导致服务长时间不可用。
> **看门狗方案**：默认给短时间（30s），业务跑得久就自动续期，业务挂了就自动过期。完美平衡了**安全性**和**可用性**。

**Q2: 解锁时为什么要用 Lua 脚本？**
> **A**: 为了保证 **"查值 + 删除"** 的原子性。
> 如果不用 Lua：
> 1. 线程 A `GET` 发现锁是自己的。
> 2. (此时锁刚好过期，线程 B 加锁成功)。
> 3. 线程 A 执行 `DEL` -> **误删了线程 B 的锁**。
> Lua 脚本将这两步合并为一个原子操作，杜绝了误删风险。

**Q3: 你的锁支持可重入吗？怎么实现的？**
> **A**: 支持。
> 我在 `RedisDistributedLock` 内部维护了一个 `ThreadLocal<Integer>` 计数器。
> 加锁时 `+1`，解锁时 `-1`。只有当计数器归零时，才会真正向 Redis 发送删除命令。

**Q4: 如果看门狗线程挂了怎么办？**
> **A**: 看门狗只是守护线程。如果它挂了（或者服务宕机了），就不会再续期。
> Redis 里的锁会在 30 秒后自动过期释放。这正是我们设计兜底过期时间 (`EX 30`) 的目的——防止死锁。
