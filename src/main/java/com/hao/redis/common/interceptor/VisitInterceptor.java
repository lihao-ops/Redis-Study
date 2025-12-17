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
 * @author hli
 * @program: RedisStudy
 * @Date 2025-12-10 20:35:02
 * @description: 访问拦截器
 */
@Slf4j
@Component
public class VisitInterceptor implements HandlerInterceptor {

    @Autowired
    private RedisClient<String> redisClient;


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 获取客户端 IP 地址 (作为 UV 的身份标识)
        String clientIp = getIpAddress(request);

        // 2. 生成今天的日期 Key，例如: "uv:daily:2023-10-25"
        // 这样我们可以做到 "每日去重"，也就是同一个 IP 今天访问 100 次只算 1 个 UV
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String dailyIpSetKey = "uv:daily:" + today;

        // 3. 【核心逻辑】利用 Set 的去重特性
        // SADD 返回 1 表示是新成员(今天第一次来)
        // SADD 返回 0 表示已经在集合里了(今天来过了)
        Long isNewVisitor = redisClient.sadd(dailyIpSetKey, clientIp);

        if (isNewVisitor != null && isNewVisitor == 1) {
            // 4. 如果是新访客，给全站总 UV 计数器 +1
            redisClient.incr(RedisKeysEnum.TOTAL_UV.getKey());

            // (可选) 给当天的 Set 设置个过期时间，比如 2 天后自动删除，节省内存
            // redisClient.expire(dailyIpSetKey, 48 * 3600);
            log.info("新访客 UV +1.IP={}", clientIp);
        }
        //5. 放行
        return true;
    }

    /**
     * 工具方法：解析 IP 地址
     * (处理反向代理 Nginx/Cloudflare 等情况)
     */
    private String getIpAddress(HttpServletRequest request) {
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
