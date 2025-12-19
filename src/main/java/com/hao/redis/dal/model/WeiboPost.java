package com.hao.redis.dal.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 微博内容实体
 *
 * 类职责：
 * 描述微博发布内容与基础属性，用于持久化与缓存传输。
 *
 * 设计目的：
 * 1. 统一微博数据结构，便于序列化与反序列化。
 * 2. 为排行与时间轴提供通用字段。
 *
 * 为什么需要该类：
 * 微博核心数据需要统一模型承载，避免散落字段定义导致结构不一致。
 *
 * 核心实现思路：
 * - 使用简单字段映射业务属性。
 * - 通过 Lombok 降低样板代码。
 */
@Data                // 自动生成访问器与字符串表示方法
@AllArgsConstructor  // 全参构造器
@NoArgsConstructor   // 无参构造器
public class WeiboPost {

    /** 微博ID（唯一标识） */
    private String postId;

    /** 发布人ID */
    private String userId;

    /** 微博内容 */
    private String content;

    /** 发布时间（为便于序列化，使用格式化字符串，如 "2023-10-01 12:00:00"） */
    private String createTime;

    // 如需统计展示，可补充点赞数等字段，发布时默认值为0
}
