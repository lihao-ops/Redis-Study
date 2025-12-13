package com.hao.redisstudy.integration.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RedisClient 接口实现：使用 StringRedisTemplate 封装五大数据类型常用命令。
 * 按照 RedisClient 接口顺序，实现并提供中文注释与示例。
 */
@Slf4j
public class RedisClientImpl implements RedisClient<String> {

    private final StringRedisTemplate redisTemplate;

    public RedisClientImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /* ------------------ 辅助校验 ------------------ */
    private void validateKey(String key, String name) {
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    private void validateParams(Object[] params, String name) {
        if (params == null || params.length == 0) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    private void validatePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
    }

    // region 字符串(String)

    /** 字符串 -> SET EX：写入并设置过期秒数。示例：SET user:1 "Tom" EX 60。 */
    @Override
    public void set(String key, String value, int expireTime) {
        validateKey(key, "key");
        validateKey(value, "value");
        validatePositive(expireTime, "expireTime");
        redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(expireTime));
    }

    /** 字符串 -> SET：覆盖写入，无过期。示例：SET user:1 "Tom"。 */
    @Override
    public void set(String key, String value) {
        validateKey(key, "key");
        validateKey(value, "value");
        redisTemplate.opsForValue().set(key, value);
    }

    /** 字符串 -> SETNX：不存在才写。示例：SETNX lock:task 1。 */
    @Override
    public Boolean setnx(String key, String value) {
        validateKey(key, "key");
        validateKey(value, "value");
        return redisTemplate.opsForValue().setIfAbsent(key, value);
    }

    /** 字符串 -> SETEX：写入并设置过期。示例：SETEX captcha:123 300 "8391"。 */
    @Override
    public void setex(String key, int expireSeconds, String value) {
        validateKey(key, "key");
        validateKey(value, "value");
        validatePositive(expireSeconds, "expireSeconds");
        redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(expireSeconds));
    }

    /** 字符串 -> GET：读取值。示例：GET user:1。 */
    @Override
    public String get(String key) {
        validateKey(key, "key");
        return redisTemplate.opsForValue().get(key);
    }

    /** 字符串 -> MGET：批量读取。示例：MGET user:1 user:2。 */
    @Override
    public List<String> mget(String... keys) {
        validateParams(keys, "keys");
        List<String> result = redisTemplate.opsForValue().multiGet(Arrays.asList(keys));
        return result != null ? result : Collections.emptyList();
    }

    /** 字符串 -> MSET：批量写入。示例：MSET a 1 b 2。 */
    @Override
    public void mset(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values 不能为空");
        }
        redisTemplate.opsForValue().multiSet(values);
    }

    /** 字符串 -> GETSET：写新值返回旧值。示例：GETSET counter 0。 */
    @Override
    public String getSet(String key, String value) {
        validateKey(key, "key");
        validateKey(value, "value");
        return redisTemplate.opsForValue().getAndSet(key, value);
    }

    /** 字符串 -> EXISTS：判断 key 是否存在。示例：EXISTS user:1。 */
    @Override
    public Boolean exists(String key) {
        validateKey(key, "key");
        return redisTemplate.hasKey(key);
    }

    /** 字符串 -> INCR：整数加 1。示例：INCR counter。 */
    @Override
    public Long incr(String key) {
        validateKey(key, "key");
        return redisTemplate.opsForValue().increment(key);
    }

    /** 字符串 -> INCRBY：整数加指定值。示例：INCRBY counter 5。 */
    @Override
    public Long incrBy(String key, long delta) {
        validateKey(key, "key");
        return redisTemplate.opsForValue().increment(key, delta);
    }

    /** 字符串 -> INCRBYFLOAT：浮点加指定值。示例：INCRBYFLOAT price 1.5。 */
    @Override
    public Double incrByFloat(String key, double delta) {
        validateKey(key, "key");
        return redisTemplate.opsForValue().increment(key, delta);
    }

    /** 字符串 -> DECR：整数减 1。示例：DECR stock。 */
    @Override
    public Long decr(String key) {
        validateKey(key, "key");
        return redisTemplate.opsForValue().decrement(key);
    }

    /** 字符串 -> DECRBY：整数减指定值。示例：DECRBY stock 2。 */
    @Override
    public Long decrBy(String key, long delta) {
        validateKey(key, "key");
        return redisTemplate.opsForValue().decrement(key, delta);
    }

    /** 字符串 -> APPEND：追加字符串。示例：APPEND log "line"。 */
    @Override
    public Long append(String key, String appendValue) {
        validateKey(key, "key");
        validateKey(appendValue, "appendValue");
        Integer appended = redisTemplate.opsForValue().append(key, appendValue);
        return appended == null ? 0L : appended.longValue();
    }

    /** 字符串 -> STRLEN：获取长度。示例：STRLEN user:bio。 */
    @Override
    public Long strlen(String key) {
        validateKey(key, "key");
        return redisTemplate.opsForValue().size(key);
    }

    /** 字符串 -> DEL：删除单个 key。示例：DEL user:1。 */
    @Override
    public Long del(String key) {
        validateKey(key, "key");
        Boolean deleted = redisTemplate.delete(key);
        return Boolean.TRUE.equals(deleted) ? 1L : 0L;
    }

    /** 字符串 -> DEL：删除多个 key。示例：DEL a b c。 */
    @Override
    public Long del(String... keys) {
        validateParams(keys, "keys");
        return redisTemplate.delete(Arrays.asList(keys));
    }

    // endregion

    // region 哈希(Hash)

    /** 哈希 -> HSET：设置字段。示例：HSET user:1 name "Tom"。 */
    @Override
    public void hset(String key, String field, String value) {
        validateKey(key, "key");
        validateKey(field, "field");
        validateKey(value, "value");
        redisTemplate.opsForHash().put(key, field, value);
    }

    /** 哈希 -> HSETNX：字段不存在才写。示例：HSETNX user:1 name "Tom"。 */
    @Override
    public Boolean hsetnx(String key, String field, String value) {
        validateKey(key, "key");
        validateKey(field, "field");
        validateKey(value, "value");
        return redisTemplate.opsForHash().putIfAbsent(key, field, value);
    }

    /** 哈希 -> HGET：读取字段。示例：HGET user:1 name。 */
    @Override
    public String hget(String key, String field) {
        validateKey(key, "key");
        validateKey(field, "field");
        Object val = redisTemplate.opsForHash().get(key, field);
        return val != null ? val.toString() : null;
    }

    /** 哈希 -> HGETALL：获取全部字段。示例：HGETALL user:1。 */
    @Override
    public Map<String, String> hgetAll(String key) {
        validateKey(key, "key");
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) {
            return Collections.emptyMap();
        }
        return entries.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().toString(),
                        e -> e.getValue().toString(),
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));
    }

    /** 哈希 -> HMSET：批量写字段。示例：HMSET user:1 name Tom age 18。 */
    @Override
    public void hmset(String key, Map<String, String> paramMap) {
        validateKey(key, "key");
        if (paramMap == null || paramMap.isEmpty()) {
            throw new IllegalArgumentException("paramMap 不能为空");
        }
        redisTemplate.opsForHash().putAll(key, paramMap);
    }

    /** 哈希 -> HMGET：批量读字段。示例：HMGET user:1 name age。 */
    @Override
    public List<String> hmget(String key, String... fields) {
        validateKey(key, "key");
        validateParams(fields, "fields");
        List<Object> vals = redisTemplate.opsForHash().multiGet(key, Arrays.asList(fields));
        return vals.stream().map(v -> v != null ? v.toString() : null).collect(Collectors.toList());
    }

    /** 哈希 -> HKEYS：列出字段名。示例：HKEYS user:1。 */
    @Override
    public Set<String> hkeys(String key) {
        validateKey(key, "key");
        Set<Object> keys = redisTemplate.opsForHash().keys(key);
        if (keys == null || keys.isEmpty()) {
            return Collections.emptySet();
        }
        return keys.stream().map(Object::toString).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 哈希 -> HVALS：列出字段值。示例：HVALS user:1。 */
    @Override
    public List<String> hvals(String key) {
        validateKey(key, "key");
        List<Object> vals = redisTemplate.opsForHash().values(key);
        if (vals == null || vals.isEmpty()) {
            return Collections.emptyList();
        }
        return vals.stream().map(v -> v != null ? v.toString() : null).collect(Collectors.toList());
    }

    /** 哈希 -> HLEN：字段数量。示例：HLEN user:1。 */
    @Override
    public Long hlen(String key) {
        validateKey(key, "key");
        return redisTemplate.opsForHash().size(key);
    }

    /** 哈希 -> HEXISTS：判断字段存在。示例：HEXISTS user:1 name。 */
    @Override
    public Boolean hexists(String key, String field) {
        validateKey(key, "key");
        validateKey(field, "field");
        return redisTemplate.opsForHash().hasKey(key, field);
    }

    /** 哈希 -> HDEL：删除字段。示例：HDEL user:1 name age。 */
    @Override
    public Long hdel(String key, String... fields) {
        validateKey(key, "key");
        validateParams(fields, "fields");
        return redisTemplate.opsForHash().delete(key, (Object[]) fields);
    }

    /** 哈希 -> HINCRBY：整数字段自增。示例：HINCRBY user:1 score 10。 */
    @Override
    public Long hincrBy(String key, String field, long delta) {
        validateKey(key, "key");
        validateKey(field, "field");
        return redisTemplate.opsForHash().increment(key, field, delta);
    }

    /** 哈希 -> HINCRBYFLOAT：浮点字段自增。示例：HINCRBYFLOAT user:1 price 1.5。 */
    @Override
    public Double hincrByFloat(String key, String field, double delta) {
        validateKey(key, "key");
        validateKey(field, "field");
        return redisTemplate.opsForHash().increment(key, field, delta);
    }

    // endregion

    // region 列表(List)

    /** 列表 -> LPUSH：左侧入队。示例：LPUSH queue a b。 */
    @Override
    public Long lpush(String key, String... values) {
        validateKey(key, "key");
        validateParams(values, "values");
        return redisTemplate.opsForList().leftPushAll(key, values);
    }

    /** 列表 -> RPUSH：右侧入队。示例：RPUSH queue a b。 */
    @Override
    public Long rpush(String key, String... values) {
        validateKey(key, "key");
        validateParams(values, "values");
        return redisTemplate.opsForList().rightPushAll(key, values);
    }

    /** 列表 -> LPOP：左出队。示例：LPOP queue。 */
    @Override
    public String lpop(String key) {
        validateKey(key, "key");
        return redisTemplate.opsForList().leftPop(key);
    }

    /** 列表 -> RPOP：右出队。示例：RPOP queue。 */
    @Override
    public String rpop(String key) {
        validateKey(key, "key");
        return redisTemplate.opsForList().rightPop(key);
    }

    /** 列表 -> BLPOP：阻塞左出队。示例：BLPOP 5 queue。 */
    @Override
    public List<String> blpop(int timeoutSeconds, String... keys) {
        validateParams(keys, "keys");
        validatePositive(timeoutSeconds, "timeoutSeconds");
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        for (String key : keys) {
            validateKey(key, "key");
            String value = redisTemplate.opsForList().leftPop(key, timeout);
            if (value != null) {
                return Arrays.asList(key, value);
            }
        }
        return null;
    }

    /** 列表 -> BRPOP：阻塞右出队。示例：BRPOP 5 queue。 */
    @Override
    public List<String> brpop(int timeoutSeconds, String... keys) {
        validateParams(keys, "keys");
        validatePositive(timeoutSeconds, "timeoutSeconds");
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        for (String key : keys) {
            validateKey(key, "key");
            String value = redisTemplate.opsForList().rightPop(key, timeout);
            if (value != null) {
                return Arrays.asList(key, value);
            }
        }
        return null;
    }

    /** 列表 -> LRANGE：区间读取。示例：LRANGE queue 0 9。 */
    @Override
    public List<String> lrange(String key, long start, long stop) {
        validateKey(key, "key");
        List<String> result = redisTemplate.opsForList().range(key, start, stop);
        return result != null ? result : Collections.emptyList();
    }

    /** 列表 -> LINDEX：按索引取值。示例：LINDEX queue 0。 */
    @Override
    public String lindex(String key, long index) {
        validateKey(key, "key");
        return redisTemplate.opsForList().index(key, index);
    }

    /** 列表 -> LSET：按索引覆盖。示例：LSET queue 0 "new"。 */
    @Override
    public void lset(String key, long index, String value) {
        validateKey(key, "key");
        validateKey(value, "value");
        redisTemplate.opsForList().set(key, index, value);
    }

    /** 列表 -> LTRIM：保留指定区间。示例：LTRIM queue 0 9。 */
    @Override
    public void ltrim(String key, long start, long stop) {
        validateKey(key, "key");
        redisTemplate.opsForList().trim(key, start, stop);
    }

    /** 列表 -> LREM：按值删除。示例：LREM queue 1 "a"。 */
    @Override
    public Long lrem(String key, long count, String value) {
        validateKey(key, "key");
        validateKey(value, "value");
        return redisTemplate.opsForList().remove(key, count, value);
    }

    /** 列表 -> RPOPLPUSH：尾取头插。示例：RPOPLPUSH src dest。 */
    @Override
    public String rpoplpush(String sourceKey, String destinationKey) {
        validateKey(sourceKey, "sourceKey");
        validateKey(destinationKey, "destinationKey");
        return redisTemplate.opsForList().rightPopAndLeftPush(sourceKey, destinationKey);
    }

    /** 列表 -> LLEN：长度查询。示例：LLEN queue。 */
    @Override
    public Long llen(String key) {
        validateKey(key, "key");
        return redisTemplate.opsForList().size(key);
    }

    // endregion

    // region 无序集合(Set)

    /** 无序集合 -> SADD：添加成员。示例：SADD tags a b。 */
    @Override
    public Long sadd(String key, String... members) {
        validateKey(key, "key");
        validateParams(members, "members");
        return redisTemplate.opsForSet().add(key, members);
    }

    /** 无序集合 -> SREM：移除成员。示例：SREM tags a b。 */
    @Override
    public Long srem(String key, String... members) {
        validateKey(key, "key");
        validateParams(members, "members");
        return redisTemplate.opsForSet().remove(key, (Object[]) members);
    }

    /** 无序集合 -> SMEMBERS：获取全部成员。示例：SMEMBERS tags。 */
    @Override
    public Set<String> smembers(String key) {
        validateKey(key, "key");
        Set<String> result = redisTemplate.opsForSet().members(key);
        return result != null ? result : Collections.emptySet();
    }

    /** 无序集合 -> SISMEMBER：判断成员存在。示例：SISMEMBER tags a。 */
    @Override
    public Boolean sismember(String key, String member) {
        validateKey(key, "key");
        validateKey(member, "member");
        return redisTemplate.opsForSet().isMember(key, member);
    }

    /** 无序集合 -> SCARD：成员数量。示例：SCARD tags。 */
    @Override
    public Long scard(String key) {
        validateKey(key, "key");
        return redisTemplate.opsForSet().size(key);
    }

    /** 无序集合 -> SPOP：随机弹出一个。示例：SPOP tags。 */
    @Override
    public String spop(String key) {
        validateKey(key, "key");
        return redisTemplate.opsForSet().pop(key);
    }

    /** 无序集合 -> SPOP count：随机弹出多个。示例：SPOP tags 2。 */
    @Override
    public Set<String> spop(String key, long count) {
        validateKey(key, "key");
        validatePositive(count, "count");
        Collection<String> result = redisTemplate.opsForSet().pop(key, count);
        if (result == null || result.isEmpty()) {
            return Collections.emptySet();
        }
        return new LinkedHashSet<>(result);
    }

    /** 无序集合 -> SRANDMEMBER：随机返回一个但不删除。示例：SRANDMEMBER tags。 */
    @Override
    public String srandmember(String key) {
        validateKey(key, "key");
        return redisTemplate.opsForSet().randomMember(key);
    }

    /** 无序集合 -> SRANDMEMBER count：随机返回多个可重复。示例：SRANDMEMBER tags 3。 */
    @Override
    public List<String> srandmember(String key, int count) {
        validateKey(key, "key");
        return redisTemplate.opsForSet().randomMembers(key, count);
    }

    /** 无序集合 -> SINTER：求交集。示例：SINTER a b。 */
    @Override
    public Set<String> sinter(String... keys) {
        validateParams(keys, "keys");
        if (keys.length == 1) {
            Set<String> result = redisTemplate.opsForSet().members(keys[0]);
            return result != null ? result : Collections.emptySet();
        }
        return redisTemplate.opsForSet().intersect(keys[0], Arrays.asList(keys).subList(1, keys.length));
    }

    /** 无序集合 -> SUNION：求并集。示例：SUNION a b。 */
    @Override
    public Set<String> sunion(String... keys) {
        validateParams(keys, "keys");
        if (keys.length == 1) {
            Set<String> result = redisTemplate.opsForSet().members(keys[0]);
            return result != null ? result : Collections.emptySet();
        }
        return redisTemplate.opsForSet().union(keys[0], Arrays.asList(keys).subList(1, keys.length));
    }

    /** 无序集合 -> SDIFF：差集。示例：SDIFF a b。 */
    @Override
    public Set<String> sdiff(String... keys) {
        validateParams(keys, "keys");
        if (keys.length == 1) {
            Set<String> result = redisTemplate.opsForSet().members(keys[0]);
            return result != null ? result : Collections.emptySet();
        }
        return redisTemplate.opsForSet().difference(keys[0], Arrays.asList(keys).subList(1, keys.length));
    }

    /** 无序集合 -> SINTERSTORE：交集存储到目标。示例：SINTERSTORE dest a b。 */
    @Override
    public Long sinterstore(String destination, String... keys) {
        validateKey(destination, "destination");
        validateParams(keys, "keys");
        String first = keys[0];
        List<String> others = keys.length > 1 ? Arrays.asList(keys).subList(1, keys.length) : Collections.emptyList();
        return redisTemplate.opsForSet().intersectAndStore(first, others, destination);
    }

    /** 无序集合 -> SUNIONSTORE：并集存储到目标。示例：SUNIONSTORE dest a b。 */
    @Override
    public Long sunionstore(String destination, String... keys) {
        validateKey(destination, "destination");
        validateParams(keys, "keys");
        String first = keys[0];
        List<String> others = keys.length > 1 ? Arrays.asList(keys).subList(1, keys.length) : Collections.emptyList();
        return redisTemplate.opsForSet().unionAndStore(first, others, destination);
    }

    /** 无序集合 -> SDIFFSTORE：差集存储到目标。示例：SDIFFSTORE dest a b。 */
    @Override
    public Long sdiffstore(String destination, String... keys) {
        validateKey(destination, "destination");
        validateParams(keys, "keys");
        String first = keys[0];
        List<String> others = keys.length > 1 ? Arrays.asList(keys).subList(1, keys.length) : Collections.emptyList();
        return redisTemplate.opsForSet().differenceAndStore(first, others, destination);
    }

    // endregion 无序集合

    // region 有序集合(Sorted Set/ZSet)

    /** 有序集合 -> ZADD：批量新增成员。示例：ZADD rank 100 user1 200 user2。 */
    @Override
    public Long zadd(String key, Map<String, Double> valueMap) {
        validateKey(key, "key");
        if (valueMap == null || valueMap.isEmpty()) {
            throw new IllegalArgumentException("valueMap 不能为空");
        }
        Set<ZSetOperations.TypedTuple<String>> tuples = valueMap.entrySet().stream()
                .map(e -> ZSetOperations.TypedTuple.of(e.getKey(), e.getValue()))
                .collect(Collectors.toSet());
        return redisTemplate.opsForZSet().add(key, tuples);
    }

    /** 有序集合 -> ZADD：新增单个成员。示例：ZADD rank 100 user1。 */
    @Override
    public Long zadd(String key, double score, String member) {
        validateKey(key, "key");
        validateKey(member, "member");
        Boolean added = redisTemplate.opsForZSet().add(key, member, score);
        return Boolean.TRUE.equals(added) ? 1L : 0L;
    }

    /** 有序集合 -> ZRANGE：按索引升序取。示例：ZRANGE rank 0 9。 */
    @Override
    public Set<String> zrange(String key, long start, long stop) {
        validateKey(key, "key");
        Set<String> result = redisTemplate.opsForZSet().range(key, start, stop);
        return result != null ? result : Collections.emptySet();
    }

    /** 有序集合 -> ZREVRANGE：按索引降序取。示例：ZREVRANGE rank 0 9。 */
    @Override
    public Set<String> zrevrange(String key, long start, long stop) {
        validateKey(key, "key");
        Set<String> result = redisTemplate.opsForZSet().reverseRange(key, start, stop);
        return result != null ? result : Collections.emptySet();
    }

    /** 有序集合 -> ZRANGEBYSCORE：按分数升序区间。示例：ZRANGEBYSCORE rank 0 100。 */
    @Override
    public Set<String> zrangeByScore(String key, double minScore, double maxScore) {
        validateKey(key, "key");
        Set<String> result = redisTemplate.opsForZSet().rangeByScore(key, minScore, maxScore);
        return result != null ? result : Collections.emptySet();
    }

    /** 有序集合 -> ZREVRANGEBYSCORE：按分数降序区间。示例：ZREVRANGEBYSCORE rank 100 0。 */
    @Override
    public Set<String> zrevrangeByScore(String key, double maxScore, double minScore) {
        validateKey(key, "key");
        Set<String> result = redisTemplate.opsForZSet().reverseRangeByScore(key, minScore, maxScore);
        return result != null ? result : Collections.emptySet();
    }

    /** 有序集合 -> ZRANK：升序排名。示例：ZRANK rank user1。 */
    @Override
    public Long zrank(String key, String member) {
        validateKey(key, "key");
        validateKey(member, "member");
        return redisTemplate.opsForZSet().rank(key, member);
    }

    /** 有序集合 -> ZREVRANK：降序排名。示例：ZREVRANK rank user1。 */
    @Override
    public Long zrevrank(String key, String member) {
        validateKey(key, "key");
        validateKey(member, "member");
        return redisTemplate.opsForZSet().reverseRank(key, member);
    }

    /** 有序集合 -> ZREMRANGEBYSCORE：按分数区间删除。示例：ZREMRANGEBYSCORE rank 0 50。 */
    @Override
    public Long zremrangeByScore(String key, double scoreMin, double scoreMax) {
        validateKey(key, "key");
        return redisTemplate.opsForZSet().removeRangeByScore(key, scoreMin, scoreMax);
    }

    /** 有序集合 -> ZREMRANGEBYRANK：按索引区间删除。示例：ZREMRANGEBYRANK rank 0 1。 */
    @Override
    public Long zremrangeByRank(String key, long start, long stop) {
        validateKey(key, "key");
        return redisTemplate.opsForZSet().removeRange(key, start, stop);
    }

    /** 有序集合 -> ZREM：删除指定成员。示例：ZREM rank user1 user2。 */
    @Override
    public Long zrem(String key, String... members) {
        validateKey(key, "key");
        validateParams(members, "members");
        return redisTemplate.opsForZSet().remove(key, (Object[]) members);
    }

    /** 有序集合 -> ZSCORE：查询成员分数。示例：ZSCORE rank user1。 */
    @Override
    public Double zscore(String key, String member) {
        validateKey(key, "key");
        validateKey(member, "member");
        return redisTemplate.opsForZSet().score(key, member);
    }

    /** 有序集合 -> ZINCRBY：分数自增。示例：ZINCRBY rank 10 user1。 */
    @Override
    public Double zincrby(String key, double increment, String member) {
        validateKey(key, "key");
        validateKey(member, "member");
        return redisTemplate.opsForZSet().incrementScore(key, member, increment);
    }

    /** 有序集合 -> ZCARD：成员数量。示例：ZCARD rank。 */
    @Override
    public Long zcard(String key) {
        validateKey(key, "key");
        return redisTemplate.opsForZSet().zCard(key);
    }

    /** 有序集合 -> ZCOUNT：按分数区间计数。示例：ZCOUNT rank 0 100。 */
    @Override
    public Long zcount(String key, double scoreMin, double scoreMax) {
        validateKey(key, "key");
        return redisTemplate.opsForZSet().count(key, scoreMin, scoreMax);
    }

    /** 有序集合 -> ZPOPMIN：按分数从小到大弹出成员。示例：ZPOPMIN rank 2。 */
    @Override
    public Set<String> zpopmin(String key, long count) {
        validateKey(key, "key");
        validatePositive(count, "count");
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet().popMin(key, count);
        if (tuples == null || tuples.isEmpty()) {
            return Collections.emptySet();
        }
        return tuples.stream().map(ZSetOperations.TypedTuple::getValue).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 有序集合 -> ZPOPMAX：按分数从大到小弹出成员。示例：ZPOPMAX rank 2。 */
    @Override
    public Set<String> zpopmax(String key, long count) {
        validateKey(key, "key");
        validatePositive(count, "count");
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet().popMax(key, count);
        if (tuples == null || tuples.isEmpty()) {
            return Collections.emptySet();
        }
        return tuples.stream().map(ZSetOperations.TypedTuple::getValue).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 有序集合 -> ZINTERSTORE：多个有序集合交集存入目标。示例：ZINTERSTORE dest 2 a b。 */
    @Override
    public Long zinterstore(String destination, String... keys) {
        validateKey(destination, "destination");
        validateParams(keys, "keys");
        String first = keys[0];
        List<String> others = keys.length > 1 ? Arrays.asList(keys).subList(1, keys.length) : Collections.emptyList();
        return redisTemplate.opsForZSet().intersectAndStore(first, others, destination);
    }

    /** 有序集合 -> ZUNIONSTORE：多个有序集合并集存入目标。示例：ZUNIONSTORE dest 2 a b。 */
    @Override
    public Long zunionstore(String destination, String... keys) {
        validateKey(destination, "destination");
        validateParams(keys, "keys");
        String first = keys[0];
        List<String> others = keys.length > 1 ? Arrays.asList(keys).subList(1, keys.length) : Collections.emptyList();
        return redisTemplate.opsForZSet().unionAndStore(first, others, destination);
    }

    // endregion 有序集合

    // region 过期控制

    /** Key 过期 -> EXPIRE：设置秒级过期。示例：EXPIRE user:1 60。 */
    @Override
    public Boolean expire(String key, int seconds) {
        validateKey(key, "key");
        validatePositive(seconds, "seconds");
        return redisTemplate.expire(key, Duration.ofSeconds(seconds));
    }

    /** Key 过期 -> EXPIREAT：按时间戳过期。示例：EXPIREAT user:1 1700000000。 */
    @Override
    public Boolean expireAt(String key, long timestamp) {
        validateKey(key, "key");
        validatePositive(timestamp, "timestamp");
        return redisTemplate.expireAt(key, new Date(timestamp * 1000));
    }

    /** Key 过期 -> PERSIST：移除过期时间。示例：PERSIST user:1。 */
    @Override
    public Boolean persist(String key) {
        validateKey(key, "key");
        return redisTemplate.persist(key);
    }

    /** Key 过期 -> TTL：查看剩余秒数。示例：TTL user:1。 */
    @Override
    public Long ttl(String key) {
        validateKey(key, "key");
        return redisTemplate.getExpire(key);
    }

    // endregion

    // region 通用

    /** 通用 -> TYPE：查看数据类型。示例：TYPE user:1。 */
    @Override
    public String type(String key) {
        validateKey(key, "key");
        DataType dataType = redisTemplate.type(key);
        return dataType != null ? dataType.code() : "none";
    }

    /** 通用 -> RENAME：强制重命名。示例：RENAME a b。 */
    @Override
    public void rename(String oldKey, String newKey) {
        validateKey(oldKey, "oldKey");
        validateKey(newKey, "newKey");
        redisTemplate.rename(oldKey, newKey);
    }

    /** 通用 -> RENAMENX：目标不存在时重命名。示例：RENAMENX a b。 */
    @Override
    public Boolean renamenx(String oldKey, String newKey) {
        validateKey(oldKey, "oldKey");
        validateKey(newKey, "newKey");
        return redisTemplate.renameIfAbsent(oldKey, newKey);
    }

    /** 通用 -> KEYS：模式匹配列出 key（生产慎用）。示例：KEYS user:*。 */
    @Override
    public Set<String> keys(String pattern) {
        validateKey(pattern, "pattern");
        Set<String> result = redisTemplate.keys(pattern);
        return result != null ? result : Collections.emptySet();
    }

    /** 通用 -> SCAN：迭代遍历 key。示例：SCAN 0 MATCH user:* COUNT 100。 */
    @Override
    public Cursor<String> scan(String pattern, long count) {
        validateKey(pattern, "pattern");
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(count).build();
        return redisTemplate.scan(options);
    }

    // endregion

    // region 扩展工具方法（便于测试或外部访问模板）

    public StringRedisTemplate getRedisTemplate() {
        return redisTemplate;
    }

    /**
     * 简易分布式锁尝试获取。示例：tryLock("lock:a", "token", 5)。
     */
    public boolean tryLock(String key, String value, int expireTime) {
        validateKey(key, "key");
        validateKey(value, "value");
        validatePositive(expireTime, "expireTime");
        Boolean success = redisTemplate.opsForValue().setIfAbsent(key, value, Duration.ofSeconds(expireTime));
        return Boolean.TRUE.equals(success);
    }

    /**
     * 释放分布式锁，脚本校验持有者。示例：releaseLock("lock:a", "token")。
     */
    public boolean releaseLock(String key, String value) {
        validateKey(key, "key");
        validateKey(value, "value");

        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "return redis.call('del', KEYS[1]) " +
                "else return 0 end";

        RedisCallback<Long> callback = connection ->
                connection.eval(
                        script.getBytes(),
                        ReturnType.INTEGER,
                        1,
                        key.getBytes(),
                        value.getBytes()
                );

        Object result = redisTemplate.execute(callback);
        return Long.valueOf(1).equals(result);
    }

    // endregion
}
