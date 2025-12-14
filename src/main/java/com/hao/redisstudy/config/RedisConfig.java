package com.hao.redisstudy.config;

import com.hao.redisstudy.integration.redis.RedisClientImpl;
import io.lettuce.core.api.StatefulConnection;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Redis 连接与 Bean 配置类，负责创建连接工厂、模板以及 RedisClient 实现。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RedisProperties.class)
public class RedisConfig {

    private final RedisProperties redisProperties;

    public RedisConfig(RedisProperties redisProperties) {
        this.redisProperties = redisProperties;
    }

    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisClusterConfiguration config = new RedisClusterConfiguration(redisProperties.getCluster().getNodes());
        if (redisProperties.getCluster().getMaxRedirects() != null) {
            config.setMaxRedirects(redisProperties.getCluster().getMaxRedirects());
        }
        if (StringUtils.hasText(redisProperties.getPassword())) {
            config.setPassword(redisProperties.getPassword());
        }

        GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig = new GenericObjectPoolConfig<>();
        RedisProperties.Pool pool = redisProperties.getLettuce().getPool();
        if (pool != null) {
            poolConfig.setMaxTotal(pool.getMaxActive());
            poolConfig.setMaxIdle(pool.getMaxIdle());
            poolConfig.setMinIdle(pool.getMinIdle());
            poolConfig.setMaxWait(pool.getMaxWait());
        }

        Duration timeout = redisProperties.getTimeout() != null ? redisProperties.getTimeout() : Duration.ofSeconds(5);

        LettuceClientConfiguration clientConfiguration = LettucePoolingClientConfiguration.builder()
                .commandTimeout(timeout)
                .poolConfig(poolConfig)
                .build();

        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(config, clientConfiguration);
        connectionFactory.setValidateConnection(true);
        connectionFactory.afterPropertiesSet();
        log.info("Redis Cluster 连接工厂创建完成，配置节点: {}", redisProperties.getCluster().getNodes());
        return connectionFactory;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        template.afterPropertiesSet();
        log.info("StringRedisTemplate 初始化完成");
        return template;
    }

    @Bean
    public com.hao.redisstudy.integration.redis.RedisClient<String> redisClient(StringRedisTemplate stringRedisTemplate) {
        return new RedisClientImpl(stringRedisTemplate);
    }

    /**
     * 启动时检查 Redis 集群连接并打印节点列表
     */
    @Bean
    public CommandLineRunner logClusterNodes(LettuceConnectionFactory factory) {
        return args -> {
            try {
                // 获取集群连接
                RedisClusterConnection connection = factory.getClusterConnection();
                log.info("============================================================");
                log.info(">>> Redis Cluster 连接成功! <<<");
                Iterable<RedisClusterNode> nodes = connection.clusterGetNodes();
                for (RedisClusterNode node : nodes) {
                    log.info(">>> 检测到集群节点: {}:{} (Type: {})", 
                            node.getHost(), node.getPort(), node.getType());
                }
                log.info("============================================================");
            } catch (Exception e) {
                log.error(">>> Redis Cluster 连接失败: {} <<<", e.getMessage(), e);
            }
        };
    }
}
