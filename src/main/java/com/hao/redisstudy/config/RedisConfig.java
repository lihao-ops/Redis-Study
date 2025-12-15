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
 * Redis 集群核心配置类
 * <p>
 * 负责构建基于 Lettuce 的 Redis 连接工厂，并针对高并发场景（如秒杀压测）进行了深度调优。
 * 特别针对 Java 虚拟线程（Virtual Threads）环境，关闭了 Lettuce 的默认连接共享机制，
 * 确保能充分利用连接池中的多条物理 TCP 连接，打破网络带宽和延迟瓶颈。
 *
 * @author hli
 * @version 1.0
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RedisProperties.class)
public class RedisConfig {

    private final RedisProperties redisProperties;

    public RedisConfig(RedisProperties redisProperties) {
        this.redisProperties = redisProperties;
    }

    /**
     * 创建并配置 Lettuce 连接工厂 (核心方法)
     * <p>
     * 这里手动组装了集群配置、连接池配置和客户端配置。
     * 关键优化点在于显式关闭了 {@code shareNativeConnection}，
     * 强制开启多连接并行模式，适配虚拟线程的高吞吐特性。
     *
     * @return LettuceConnectionFactory 配置好的连接工厂
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        // --- 1. 配置 Redis 集群节点信息 ---
        // 从 application.yml 读取节点列表 (192.168.254.x:6401)
        RedisClusterConfiguration config = new RedisClusterConfiguration(redisProperties.getCluster().getNodes());

        // 设置最大重定向次数 (防止集群拓扑变更时的死循环)
        if (redisProperties.getCluster().getMaxRedirects() != null) {
            config.setMaxRedirects(redisProperties.getCluster().getMaxRedirects());
        }
        // 设置集群密码
        if (StringUtils.hasText(redisProperties.getPassword())) {
            config.setPassword(redisProperties.getPassword());
        }

        // --- 2. 配置连接池参数 (GenericObjectPool) ---
        // Lettuce 使用 Commons-Pool2 来管理连接
        GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig = new GenericObjectPoolConfig<>();
        RedisProperties.Pool pool = redisProperties.getLettuce().getPool();
        if (pool != null) {
            // 最大连接数：压测时建议设置为 1000 或更高，配合虚拟线程使用
            poolConfig.setMaxTotal(pool.getMaxActive());
            // 最大空闲连接：保持较高的水位，避免频繁创建销毁连接
            poolConfig.setMaxIdle(pool.getMaxIdle());
            // 最小空闲连接：保留底座连接
            poolConfig.setMinIdle(pool.getMinIdle());
            // 获取连接最大等待时间：建议 3-5秒，超时则抛出异常
            poolConfig.setMaxWait(pool.getMaxWait());
        }

        // 设置默认命令超时时间 (默认5秒)
        Duration timeout = redisProperties.getTimeout() != null ? redisProperties.getTimeout() : Duration.ofSeconds(5);

        // --- 3. 构建 Lettuce 客户端配置 ---
        // 使用 Pooling (池化) 模式构建配置
        LettuceClientConfiguration clientConfiguration = LettucePoolingClientConfiguration.builder()
                .commandTimeout(timeout)
                .poolConfig(poolConfig)
                .build();

        // --- 4. 实例化连接工厂 ---
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(config, clientConfiguration);

        // 开启连接校验，确保获取到的连接是可用的
        connectionFactory.setValidateConnection(true);

        // 🔥🔥🔥🔥【核心性能优化】🔥🔥🔥🔥
        // 默认值：true (开启共享)。开启时，Lettuce 会复用同一条物理 TCP 连接来发送所有命令（除非是事务/阻塞命令）。
        // 性能瓶颈：在高并发下，这条单连接会成为物理瓶颈，TPS 被锁死在 2000-3000 左右。
        // 优化方案：设置为 false (关闭共享)。
        // 作用：配合连接池，强制让每个 Redis 操作都从池中获取一个独立的、独占的物理连接。
        // 结果：如果有 1000 个连接，就能同时有 1000 个 TCP 通道在传输数据，吞吐量成倍提升！
        connectionFactory.setShareNativeConnection(true);
        // 初始化工厂
        connectionFactory.afterPropertiesSet();
        log.info("🚀 Redis Cluster 连接工厂创建完成 | 节点: {} | 连接池上限: {} | 共享连接模式: 关闭",
                redisProperties.getCluster().getNodes(),
                poolConfig.getMaxTotal());

        return connectionFactory;
    }

    /**
     * 配置 StringRedisTemplate
     * <p>
     * 这是一个针对 String 类型优化的模板，Key 和 Value 都是 String 序列化。
     * 也是压测中最常用的模板。
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        // 初始化设置，确保所有组件加载完毕
        template.afterPropertiesSet();
        log.info("✅ StringRedisTemplate 初始化完成");
        return template;
    }

    /**
     * 配置自定义的 RedisClient 封装类
     */
    @Bean
    public com.hao.redisstudy.integration.redis.RedisClient<String> redisClient(StringRedisTemplate stringRedisTemplate) {
        return new RedisClientImpl(stringRedisTemplate);
    }

    /**
     * 启动时健康检查
     * <p>
     * 在 Spring 容器启动完成后，尝试连接 Redis 集群并打印所有可用节点。
     * 用于快速验证集群配置是否正确。
     */
    @Bean
    public CommandLineRunner logClusterNodes(LettuceConnectionFactory factory) {
        return args -> {
            try {
                // 获取集群连接对象
                RedisClusterConnection connection = factory.getClusterConnection();
                log.info("============================================================");
                log.info(">>> 🎉 Redis Cluster 连接成功! 准备起飞... <<<");

                // 获取并遍历所有节点信息
                Iterable<RedisClusterNode> nodes = connection.clusterGetNodes();
                for (RedisClusterNode node : nodes) {
                    log.info(">>> 🌍 检测到集群节点: {}:{} (角色: {})",
                            node.getHost(), node.getPort(), node.getType());
                }
                log.info("============================================================");
            } catch (Exception e) {
                log.error(">>> ❌ Redis Cluster 连接失败，请检查防火墙或配置: {} <<<", e.getMessage(), e);
            }
        };
    }
}