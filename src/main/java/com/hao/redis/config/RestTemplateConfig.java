package com.hao.redis.config;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        // 支持高并发（目标 1000 QPS）
        // 之前的数值 (200) 对于线程池大小来说太低了
        connectionManager.setMaxTotal(2000); 
        
        // 关键：之前的数值 (50) 是瓶颈所在。
        // 50 个连接 * (1秒 / 0.46秒延迟) ≈ 108 QPS 最大容量。
        // 增加到 2000 以允许本地全速吞吐。
        connectionManager.setDefaultMaxPerRoute(2000); 

        CloseableHttpClient httpClient = HttpClients.custom()
            .setConnectionManager(connectionManager)
            .build();

        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }
}