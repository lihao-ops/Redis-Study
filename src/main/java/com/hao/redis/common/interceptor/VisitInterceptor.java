package com.hao.redis.common.interceptor;

import com.hao.redis.common.enums.RedisKeysEnum;
import com.hao.redis.integration.redis.RedisClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 访问统计拦截器
 *
 * 类职责：
 * 统计全站 UV 并进行每日去重。
 *
 * 设计目的：
 * 1. 统一入口统计访问量，避免业务代码侵入。
 * 2. 通过集合去重实现轻量级UV统计。
 *
 * 为什么需要该类：
 * 访问统计是横切能力，通过拦截器集中处理更易维护与扩展。
 *
 * 核心实现思路：
 * - 使用IP作为访客标识。
 * - 以日期维度维护去重集合并累加全站UV。
 */
@Slf4j
@Component
public class VisitInterceptor implements HandlerInterceptor {

    @Autowired
    private RedisClient<String> redisClient;


    /**
     * 拦截请求并统计 UV
     *
     * 实现逻辑：
     * 1. 提取客户端 IP。
     * 2. 以日期为维度进行去重。
     * 3. 新访客则累加全站 UV。
     *
     * @param request 请求对象
     * @param response 响应对象
     * @param handler 处理器对象
     * @return 是否放行
     * @throws Exception 处理异常
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 实现思路：
        // 1. 提取 IP 作为访客标识。
        // 2. 以日期维度去重并更新 UV。
        // 1. 获取客户端IP地址（作为UV身份标识）
        String clientIp = getIpAddress(request);

        // 2. 生成今天的日期键，例如："uv:daily:2023-10-25"
        // 这样可以做到“每日去重”，同一个IP今天访问100次只算1个UV
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String dailyIpSetKey = "uv:daily:" + today;

        // 3. 【核心逻辑】利用集合去重特性
        // SADD返回1表示是新成员（今天第一次来）
        // SADD返回0表示已存在（今天来过）
        Long isNewVisitor = redisClient.sadd(dailyIpSetKey, clientIp);

        if (isNewVisitor != null && isNewVisitor == 1) {
            // 4. 如果是新访客，给全站UV计数器+1
            redisClient.incr(RedisKeysEnum.TOTAL_UV.getKey());

            // （可选）给当天的去重集合设置过期时间，比如2天后自动删除，节省内存
            // redisClient.expire(dailyIpSetKey, 48 * 3600);
            log.info("新访客UV计数+1|New_uv_increment,ip={}", clientIp);
        }
        // 5. 放行
        return true;
    }

    /**
     * 解析客户端 IP 地址
     *
     * 实现逻辑：
     * 1. 依次读取常见代理头信息。
     * 2. 兜底读取远程地址。
     *
     * @param request 请求对象
     * @return 客户端 IP
     */
    private String getIpAddress(HttpServletRequest request) {
        // 实现思路：
        // 1. 读取代理头，优先使用真实 IP。
        // 2. 无代理头时读取远程地址。
        String ip = request.getHeader("x-forwarded-for");
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
