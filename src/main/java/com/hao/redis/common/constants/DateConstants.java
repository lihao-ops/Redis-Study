package com.hao.redis.common.constants;

import java.time.format.DateTimeFormatter;

/**
 * 日期时间常量定义
 *
 * 类职责：
 * 集中管理项目中使用的日期格式化器与时间模式字符串。
 *
 * 设计目的：
 * 1. 避免在业务代码中重复创建 DateTimeFormatter 对象（线程安全且创建昂贵）。
 * 2. 统一全站的时间格式标准，便于维护。
 *
 * 为什么需要该类：
 * DateTimeFormatter 是线程安全的，应当作为全局单例复用，减少 GC 压力。
 */
public class DateConstants {

    /**
     * 标准日期时间格式模式
     * 示例：2023-10-27 14:30:00
     */
    public static final String STANDARD_DATETIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /**
     * 标准日期格式模式
     * 示例：2023-10-27
     */
    public static final String STANDARD_DATE_PATTERN = "yyyy-MM-dd";

    /**
     * 紧凑型日期格式模式（常用于生成文件名或ID）
     * 示例：20231027
     */
    public static final String COMPACT_DATE_PATTERN = "yyyyMMdd";

    /**
     * 标准日期时间格式化器 (线程安全)
     * 建议优先使用此常量，而非每次调用 DateTimeFormatter.ofPattern()
     */
    public static final DateTimeFormatter STANDARD_DATETIME_FORMATTER = DateTimeFormatter.ofPattern(STANDARD_DATETIME_PATTERN);

    /**
     * 标准日期格式化器 (线程安全)
     */
    public static final DateTimeFormatter STANDARD_DATE_FORMATTER = DateTimeFormatter.ofPattern(STANDARD_DATE_PATTERN);

    /**
     * 紧凑型日期格式化器 (线程安全)
     */
    public static final DateTimeFormatter COMPACT_DATE_FORMATTER = DateTimeFormatter.ofPattern(COMPACT_DATE_PATTERN);

    // 私有构造防止实例化
    private DateConstants() {}
}
