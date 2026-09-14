package zzz.service.forum.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public class RedisService {
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
