# 限流接入与原理说明

## 接入确认结论
- 已接入单机令牌桶与分布式RedisLua脚本限流两条链路。
- 全局入口由 `GlobalRateLimitFilter#doFilter` 执行，先走 `SimpleRateLimiter#tryAcquire`，再走 `RedisRateLimiter#tryAcquire`。
- 接口级别由 `@SimpleRateLimit` 注解配合 `SimpleRateLimitAspect#around` 执行，支持单机与分布式两种模式。
- 典型接入点：`WeiboController#createPost` 使用 `@SimpleRateLimit(qps=RateLimitConstants.WEIBO_CREATE_QPS, type=DISTRIBUTED)`。
- 限流触发后抛出 `RateLimitException`，由 `GlobalExceptionHandler#handleRateLimitException` 统一返回429。

## 生效原因与前置条件
- `RedisApplication` 使用 `@SpringBootApplication`，扫描 `com.hao.redis` 全包，`GlobalRateLimitFilter`、`SimpleRateLimiter`、`RedisRateLimiter`、`SimpleRateLimitAspect` 均为 `@Component`，可被容器加载。
- `pom.xml` 引入 `spring-boot-starter-aop`，确保 `@Aspect` 生效。
- `RedisConfig#stringRedisTemplate` 提供 `StringRedisTemplate`，满足 `RedisRateLimiter` 构造依赖。
- `GlobalRateLimitFilter` 实现 `Filter` 且标注 `@Order(1)`，在Servlet过滤器链路中优先执行。
- 运行时需具备Web环境与Redis连接，满足上述条件后限流即生效。

## 设计原因与原则
- 单机令牌桶优先：内存级限流开销低，先拦截可显著降低Redis压力。
- 分布式限流兜底：通过Redis统一集群总量，避免单机放行导致全局超载。
- 故障降级策略：`RedisRateLimiter#tryAcquire` 捕获异常后降级到本地保守限流，避免异常时完全放行。
- 全局阈值集中管理：`RateLimitConstants` 统一维护QPS，便于压测与调整。
- 注解驱动接口限流：细粒度控制关键接口，避免全局阈值影响低风险接口。
- 限流键规范：优先使用注解显式键，其次使用请求匹配路径，避免动态路径导致键膨胀。

## 核心原理
### 单机令牌桶（Guava）
- `SimpleRateLimiter#tryAcquire` 使用 `RateLimiter.tryAcquire()`。
- 按QPS生成令牌并控制速率，获取失败即触发限流。

### 分布式RedisLua脚本
- `RedisRateLimiter#tryAcquire` 使用Lua脚本在Redis端原子执行：
  - `INCR` 计数；
  - 首次计数时 `EXPIRE` 设置窗口；
  - 超过阈值返回0。
- 统一使用 `rate_limit:` 前缀，避免业务键冲突。
- `GlobalRateLimitFilter` 使用固定键 `global_service_limit`，窗口秒数为1。
- Redis异常时降级为本地保守限流，保守比例由 `rate.limit.redis-fallback-ratio` 控制。

## 配置项说明
- `rate.limit.global-qps`：全局限流阈值，默认2500。
- `rate.limit.weibo-create-qps`：发布微博接口阈值，默认10。
- `rate.limit.redis-fallback-ratio`：Redis异常时的本地保守阈值比例，默认0.5。

## 设计决策记录
- Lua脚本在Redis端原子执行，已规避非原子更新风险。
- 分层限流采用“分布式主控+单机兜底”策略，兼顾全局控制与故障保底。
- Redis异常时采用本地保守限流，优先保证可用性但避免无限放行。
- 限流键优先使用匹配路径或注解显式键，降低动态路径导致的键膨胀风险。

## 请求完整流程（含触发点）
### 全局入口限流流程
1. 请求进入Servlet过滤器链路。
2. `GlobalRateLimitFilter#doFilter`
   - 调用 `SimpleRateLimiter#tryAcquire(GLOBAL_LIMIT_KEY, QPS)`。
   - 若返回false：抛出 `RateLimitException`，进入 `GlobalExceptionHandler#handleRateLimitException`，返回429。
3. `GlobalRateLimitFilter#doFilter`
   - 调用 `RedisRateLimiter#tryAcquire(GLOBAL_LIMIT_KEY, (int)QPS, 1)`。
   - Lua脚本返回0：抛出 `RateLimitException`，返回429。
   - Redis异常：降级到本地保守限流，仍可能触发限流拦截。
4. 放行 `FilterChain#doFilter`，进入 `DispatcherServlet` 与控制器。

### 接口注解限流流程（以发布微博为例）
1. 请求进入 `WeiboController#createPost` 之前。
2. `SimpleRateLimitAspect#around`
   - `resolveRateLimitKey` 优先读取注解限流键，其次读取请求匹配路径。
   - `parseQps` 解析注解QPS配置。
3. `SimpleRateLimitAspect#around`
   - 调用 `SimpleRateLimiter#tryAcquire(uri, qps)`。
   - 若返回false：抛出 `RateLimitException`，返回429。
4. 若注解 `type=DISTRIBUTED`：
   - 调用 `RedisRateLimiter#tryAcquire(uri, (int)qps, 1)`。
   - Lua脚本返回0：抛出 `RateLimitException`，返回429。
   - Redis异常：降级到本地保守限流。
5. 限流通过后执行 `WeiboController#createPost`，再进入 `WeiboServiceImpl#createPost`。

## 触发限流时的表现
- 全局限流或接口限流触发后抛出 `RateLimitException`。
- `GlobalExceptionHandler#handleRateLimitException` 统一返回429与限流提示文案。
