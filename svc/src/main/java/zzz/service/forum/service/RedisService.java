package zzz.service.forum.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.*;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public class RedisService {

    private static final String INCREMENT_WITH_EXPIRE_SCRIPT =
            "local value = redis.call('incrby', KEYS[1], ARGV[1]); " +
                    "redis.call('expire', KEYS[1], ARGV[2]); return value";

    private static final String INCREMENT_WITH_INITIAL_EXPIRE_SCRIPT =
            "local value = redis.call('incrby', KEYS[1], ARGV[1]); " +
                    "if value == tonumber(ARGV[1]) then redis.call('expire', KEYS[1], ARGV[2]); end; " +
                    "return value";

    private static final String CONSUME_IF_MATCH_SCRIPT =
            "local value = redis.call('get', KEYS[1]); " +
                    "redis.call('del', KEYS[1]); " +
                    "if value and value == ARGV[1] then return 1 end; return 0";

    @Autowired
    @SuppressWarnings("rawtypes")
    private RedisTemplate redisTemplate;

    public <T> void setCacheObject(String key, T value) {
        this.redisTemplate.opsForValue().set(key, value);
    }

    public <T> void setCacheObject(String key, T value, Long timeout, TimeUnit timeUnit) {
        this.redisTemplate.opsForValue().set(key, value, timeout, timeUnit);
    }

    public boolean expire(String key, long timeout) {
        return this.expire(key, timeout, TimeUnit.SECONDS);
    }

    public boolean expire(String key, long timeout, TimeUnit unit) {
        Boolean result = this.redisTemplate.expire(key, timeout, unit);
        return result != null && result;
    }

    public Long getExpire(String key) {
        return this.redisTemplate.getExpire(key, TimeUnit.SECONDS);
    }

    public Boolean hasKey(String key) {
        return this.redisTemplate.hasKey(key);
    }

    public <T> T getCacheObject(String key) {
        ValueOperations<String, T> operation = this.redisTemplate.opsForValue();
        return operation.get(key);
    }

    public boolean deleteObject(String key) {
        Boolean result = this.redisTemplate.delete(key);
        return result != null && result;
    }

    public long deleteObject(Collection<String> keys) {
        Long count = this.redisTemplate.delete(keys);
        return count != null ? count : 0L;
    }

    public <T> long setCacheList(String key, List<T> dataList) {
        Long count = this.redisTemplate.opsForList().rightPushAll(key, dataList);
        return count == null ? 0L : count;
    }

    public <T> List<T> getCacheList(String key) {
        return this.redisTemplate.opsForList().range(key, 0L, -1L);
    }

    public <T> BoundSetOperations<String, T> setCacheSet(String key, Set<T> dataSet) {
        BoundSetOperations<String, T> setOperation = this.redisTemplate.boundSetOps(key);
        for (T item : dataSet) {
            setOperation.add(item);
        }
        return setOperation;
    }

    public <T> Set<T> getCacheSet(String key) {
        return this.redisTemplate.opsForSet().members(key);
    }

    public <T> void setCacheMap(String key, Map<String, T> dataMap) {
        if (dataMap != null) {
            this.redisTemplate.opsForHash().putAll(key, dataMap);
        }
    }

    public <T> Map<String, T> getCacheMap(String key) {
        return this.redisTemplate.opsForHash().entries(key);
    }

    public <T> void setCacheMapValue(String key, String hKey, T value) {
        this.redisTemplate.opsForHash().put(key, hKey, value);
    }

    public <T> T getCacheMapValue(String key, String hKey) {
        HashOperations<String, String, T> opsForHash = this.redisTemplate.opsForHash();
        return opsForHash.get(key, hKey);
    }

    public <T> List<T> getMultiCacheMapValue(String key, Collection<Object> hKeys) {
        return this.redisTemplate.opsForHash().multiGet(key, hKeys);
    }

    /**
     * Keys matching a pattern (e.g. "prefix:*", "prefix:???", "*keyword*").
     * Uses KEYS command - fine for small datasets, but prefer scanKeys for production.
     */
    public Set<String> keys(String pattern) {
        return this.redisTemplate.keys(pattern);
    }

    /**
     * Find all keys starting with the given prefix.
     * Equivalent to KEYS "prefix*".
     */
    public Set<String> keysWithPrefix(String prefix) {
        return this.redisTemplate.keys(prefix + "*");
    }

    /**
     * Scan keys matching a pattern using the SCAN cursor-based approach.
     * Safe for large datasets as it doesn't block Redis.
     */
    public Set<String> scanKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(pattern)
                .count(100)
                .build();
        try (Cursor<byte[]> cursor = this.redisTemplate.getConnectionFactory()
                .getConnection()
                .scan(options)) {
            while (cursor.hasNext()) {
                keys.add(new String(cursor.next()));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to scan keys with pattern: " + pattern, e);
        }
        return keys;
    }

    /**
     * Scan keys starting with the given prefix using SCAN.
     */
    public Set<String> scanKeysWithPrefix(String prefix) {
        return scanKeys(prefix + "*");
    }

    /**
     * Delete all keys matching a pattern using SCAN (safe for large datasets).
     */
    public long deleteKeysByPattern(String pattern) {
        Set<String> keys = scanKeys(pattern);
        if (keys.isEmpty()) {
            return 0L;
        }
        Long count = this.redisTemplate.delete(keys);
        return count != null ? count : 0L;
    }

    /**
     * Delete all keys starting with the given prefix.
     */
    public long deleteKeysWithPrefix(String prefix) {
        return deleteKeysByPattern(prefix + "*");
    }

    /**
     * Increment a numeric value stored at key by delta.
     */
    public Long increment(String key, long delta) {
        return this.redisTemplate.opsForValue().increment(key, delta);
    }

    /**
     * 原子递增并设置 TTL，避免 INCR 与 EXPIRE 之间进程退出留下永久键。
     */
    public Long incrementAndExpire(String key, long delta, long timeoutSeconds) {
        return evalIncrementScript(INCREMENT_WITH_EXPIRE_SCRIPT, key, delta, timeoutSeconds);
    }

    /**
     * 原子递增，并仅在新窗口第一次写入时设置 TTL，保持固定窗口限流语义。
     */
    public Long incrementWithInitialExpire(String key, long delta, long timeoutSeconds) {
        return evalIncrementScript(INCREMENT_WITH_INITIAL_EXPIRE_SCRIPT, key, delta, timeoutSeconds);
    }

    private Long evalIncrementScript(String script, String key, long delta, long timeoutSeconds) {
        byte[] keyBytes = redisTemplate.getKeySerializer().serialize(key);
        byte[] deltaBytes = String.valueOf(delta).getBytes(StandardCharsets.UTF_8);
        byte[] timeoutBytes = String.valueOf(timeoutSeconds).getBytes(StandardCharsets.UTF_8);
        Object result = redisTemplate.execute((RedisCallback<Object>) connection ->
                connection.eval(
                        script.getBytes(StandardCharsets.UTF_8),
                        ReturnType.INTEGER,
                        1,
                        keyBytes,
                        deltaBytes,
                        timeoutBytes
                ));
        return result == null ? null : ((Number) result).longValue();
    }

    /**
     * 原子比较并消费一次性值。无论答案是否匹配，只要键存在就会被删除，
     * 防止验证码并发重放。
     */
    public boolean consumeIfMatches(String key, String expected) {
        byte[] keyBytes = redisTemplate.getKeySerializer().serialize(key);
        byte[] expectedBytes = redisTemplate.getValueSerializer().serialize(expected);
        Object result = redisTemplate.execute((RedisCallback<Object>) connection ->
                connection.eval(
                        CONSUME_IF_MATCH_SCRIPT.getBytes(StandardCharsets.UTF_8),
                        ReturnType.INTEGER,
                        1,
                        keyBytes,
                        expectedBytes
                ));
        return result != null && ((Number) result).longValue() == 1L;
    }

    /**
     * Decrement a numeric value stored at key by delta.
     */
    public Long decrement(String key, long delta) {
        return this.redisTemplate.opsForValue().increment(key, -delta);
    }

    /**
     * Set a key only if it does not already exist.
     */
    public Boolean setIfAbsent(String key, Object value, long timeout, TimeUnit timeUnit) {
        return this.redisTemplate.opsForValue().setIfAbsent(key, value, timeout, timeUnit);
    }

    /**
     * Set a key only if it already exists.
     */
    public Boolean setIfPresent(String key, Object value, long timeout, TimeUnit timeUnit) {
        return this.redisTemplate.opsForValue().setIfPresent(key, value, timeout, timeUnit);
    }
}
