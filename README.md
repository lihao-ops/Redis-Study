# Redis Study - Anime Cache Explorer

这是一个基于 Spring Boot 的 Redis 学习项目，通过模拟微博（Weibo）的核心功能，演示了 Redis 各种数据结构（String, Hash, List, Sorted Set）在实际高并发业务场景中的应用。

## 项目简介

本项目旨在展示如何使用 Redis 实现高性能的社交网络功能，包括用户管理、时间线（Timeline）、点赞互动以及热搜排行。

## 技术栈

*   **开发语言**: Java 21
*   **核心框架**: Spring Boot 3.5.3
*   **缓存/NoSQL**: Spring Data Redis
*   **数据库**: MySQL / MyBatis (集成在依赖中)
*   **工具**: Lombok

## 核心功能与 Redis 设计模式

本项目通过 `WeiboService` 接口实现了以下业务场景，每个场景都对应了特定的 Redis 数据结构策略：

### 1. 用户系统 (String & Hash)
*   **注册新用户**
    *   **逻辑**: 生成自增 ID 并存储用户对象。
    *   **Redis 命令**: `INCR global:userid` (生成 ID) -> `HMSET user:{id}` (存储字段)。
*   **获取用户详情**
    *   **逻辑**: 读取用户哈希表。
    *   **Redis 命令**: `HGETALL user:{id}`。

### 2. 统计功能 (String)
*   **全站 UV 统计**
    *   **逻辑**: 获取简单的全局计数器。
    *   **Redis 命令**: `GET total:uv`。

### 3. 微博时间线 (List)
*   **发布微博**
    *   **逻辑**: 将新微博推入时间线头部，实现“最新发布在最前”。
    *   **Redis 命令**: `LPUSH timeline`。
*   **获取最新动态**
    *   **逻辑**: 分页拉取时间线列表。
    *   **Redis 命令**: `LRANGE timeline 0 19` (获取前 20 条)。

### 4. 互动与排行 (Sorted Set / ZSet)
*   **点赞微博**
    *   **逻辑**: 记录点赞行为（去重）并增加热度分。
    *   **Redis 命令**: `ZADD weibo:likes` (记录用户) + `ZINCRBY rank:hot` (增加热度)。
*   **热搜排行榜**
    *   **逻辑**: 获取热度最高的 Top N 微博。
    *   **Redis 命令**: `ZREVRANGE rank:hot 0 9` (倒序取前 10 名)。

## 快速开始

1.  确保本地已安装并启动 **Redis** 服务。
2.  在配置文件中设置 Redis 连接信息（Host, Port）。
3.  启动 Spring Boot 应用，控制台将打印自定义的 Redis ASCII Banner。