package com.hao.redis.dal.dao.mapper;

import org.apache.ibatis.annotations.Mapper;

/**
 * 微博数据访问接口
 *
 * 类职责：
 * 定义微博数据的持久化访问入口。
 *
 * 设计目的：
 * 1. 与 Redis 缓存形成分层解耦的数据访问结构。
 * 2. 为后续 MyBatis 映射扩展预留统一接口。
 *
 * 为什么需要该类：
 * 数据访问层需要统一入口，便于扩展数据库或切换实现。
 *
 * 核心实现思路：
 * - 使用 MyBatis Mapper 声明式定义数据库访问方法。
 * - 当前作为占位接口，便于逐步引入持久化能力。
 */
@Mapper
public interface WeiboMapper {

}
