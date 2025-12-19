package com.hao.redis.config;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate 客户端配置
 *
 * 类职责：
 * 构建带连接池的 RestTemplate，支撑压测与高并发调用场景。
 *
 * 设计目的：
 * 1. 提升 HTTP 连接复用率，降低频繁建连的延迟与资源消耗。
 * 2. 通过连接池规模配置消除客户端侧吞吐瓶颈。
 *
 * 为什么需要该类：
 * 默认 RestTemplate 无连接池支撑，高并发下容易出现连接耗尽与性能瓶颈。
 *
 * 核心实现思路：
 * - 通过 HttpClient 连接池管理连接。
 * - 将连接池注入到 RestTemplate 请求工厂。
 */
@Configuration
public class RestTemplateConfig {

    /**
     * 构建带连接池的 RestTemplate
     *
     * 实现逻辑：
     * 1. 初始化连接池并设置最大连接数。
     * 2. 构建 HttpClient 并注入请求工厂。
     * 3. 返回具备连接池能力的 RestTemplate 实例。
     *
     * @return RestTemplate 客户端
     */
    @Bean
    public RestTemplate restTemplate() {
        // 实现思路：
        // 1. 配置连接池容量以支撑压测并发。
        // 2. 注入到 RestTemplate 的请求工厂。
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        // 关键配置：总连接数上限
        connectionManager.setMaxTotal(2000);

        // 关键配置：单路由连接上限
        connectionManager.setDefaultMaxPerRoute(2000);

        // 核心构建：创建带连接池的 HttpClient
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .build();

        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }
}
