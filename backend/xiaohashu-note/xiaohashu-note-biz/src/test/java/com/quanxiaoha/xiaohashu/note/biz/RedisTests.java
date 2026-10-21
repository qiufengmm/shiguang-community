package com.quanxiaoha.xiaohashu.note.biz;

import cn.hutool.core.util.RandomUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Slf4j
class RedisTests {

    private static final int ELEMENT_COUNT = 10_000;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * SET 测试
     */
    @Test
    void testAddSet() {
        String key = buildKey("myset");
        Set<Integer> values = randomIntegers();

        try {
            redisTemplate.opsForSet().add(key, values.toArray());

            assertEquals(ELEMENT_COUNT, redisTemplate.opsForSet().size(key));
            log.info("SET memory usage: {} bytes", getMemoryUsage(key));
        } finally {
            redisTemplate.delete(key);
        }
    }

    /**
     * Bloom 测试
     */
    @Test
    void testAddBloom() {
        String key = buildKey("mybloom");
        Set<Integer> values = randomIntegers();
        RedisScript<Long> addScript = new DefaultRedisScript<>(
                "return redis.call('BF.ADD', KEYS[1], ARGV[1])", Long.class);
        RedisScript<Long> existsScript = new DefaultRedisScript<>(
                "return redis.call('BF.EXISTS', KEYS[1], ARGV[1])", Long.class);

        try {
            for (Integer value : values) {
                redisTemplate.execute(addScript, Collections.singletonList(key), value);
            }

            Integer insertedValue = values.iterator().next();
            assertEquals(1L, redisTemplate.execute(existsScript, Collections.singletonList(key), insertedValue));
            log.info("Bloom memory usage: {} bytes", getMemoryUsage(key));
        } finally {
            redisTemplate.delete(key);
        }
    }

    /**
     * Bitmap 测试
     */
    @Test
    void testAddBitmap() {
        String key = buildKey("mybitmap");
        Set<Long> offsets = randomOffsets();
        RedisScript<Long> setBitScript = new DefaultRedisScript<>(
                "return redis.call('SETBIT', KEYS[1], ARGV[1], ARGV[2])", Long.class);
        RedisScript<Long> bitCountScript = new DefaultRedisScript<>(
                "return redis.call('BITCOUNT', KEYS[1])", Long.class);

        try {
            for (Long offset : offsets) {
                redisTemplate.execute(setBitScript, Collections.singletonList(key), offset, 1L);
            }

            assertEquals((long) ELEMENT_COUNT,
                    redisTemplate.execute(bitCountScript, Collections.singletonList(key)));
            log.info("Bitmap memory usage: {} bytes", getMemoryUsage(key));
        } finally {
            redisTemplate.delete(key);
        }
    }

    /**
     * Roaring Bitmap 测试
     */
    @Test
    void testAddRBitmap() {
        String key = buildKey("myrbitmap");
        Set<Long> offsets = randomOffsets();
        RedisScript<Long> setBitScript = new DefaultRedisScript<>(
                "return redis.call('R.SETBIT', KEYS[1], ARGV[1], ARGV[2])", Long.class);
        RedisScript<Long> bitCountScript = new DefaultRedisScript<>(
                "return redis.call('R.BITCOUNT', KEYS[1])", Long.class);

        try {
            for (Long offset : offsets) {
                redisTemplate.execute(setBitScript, Collections.singletonList(key), offset, 1L);
            }

            assertEquals((long) ELEMENT_COUNT,
                    redisTemplate.execute(bitCountScript, Collections.singletonList(key)));
            log.info("Roaring Bitmap memory usage: {} bytes", getMemoryUsage(key));
        } finally {
            redisTemplate.delete(key);
        }
    }

    private String buildKey(String prefix) {
        return prefix + ":26.2:" + UUID.randomUUID();
    }

    private Set<Integer> randomIntegers() {
        Set<Integer> values = new HashSet<>(ELEMENT_COUNT);
        while (values.size() < ELEMENT_COUNT) {
            values.add(RandomUtil.randomInt(90_000_000));
        }
        return values;
    }

    private Set<Long> randomOffsets() {
        Set<Long> offsets = new HashSet<>(ELEMENT_COUNT);
        while (offsets.size() < ELEMENT_COUNT) {
            offsets.add(RandomUtil.randomLong(10_000_000));
        }
        return offsets;
    }

    private Long getMemoryUsage(String key) {
        RedisScript<Long> memoryUsageScript = new DefaultRedisScript<>(
                "return redis.call('MEMORY', 'USAGE', KEYS[1])", Long.class);
        Long memoryUsage = redisTemplate.execute(memoryUsageScript, Collections.singletonList(key));
        assertNotNull(memoryUsage);
        assertTrue(memoryUsage > 0);
        return memoryUsage;
    }
}
