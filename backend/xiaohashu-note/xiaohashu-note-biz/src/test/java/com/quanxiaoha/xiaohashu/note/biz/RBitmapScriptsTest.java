package com.quanxiaoha.xiaohashu.note.biz;

import cn.hutool.core.util.RandomUtil;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class RBitmapScriptsTest {

    private static final long EXPIRE_SECONDS = 86_400L;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Test
    void shouldRunLikeRoaringBitmapScriptsAgainstRedis() {
        String key = "rbitmap:like:test:" + UUID.randomUUID();
        long existingNoteId = RandomUtil.randomLong(1_000_000);
        long targetNoteId = existingNoteId + 1;
        long addedNoteId = existingNoteId + 2;

        try {
            assertEquals(-1L, execute("rbitmap_note_like_check.lua", key, targetNoteId));
            assertEquals(0L, execute("rbitmap_batch_add_note_like_and_expire.lua", key,
                    existingNoteId, EXPIRE_SECONDS));
            assertTrue(redisTemplate.getExpire(key, TimeUnit.SECONDS) > 0);

            assertEquals(0L, execute("rbitmap_note_like_check.lua", key, targetNoteId));
            assertEquals(1L, execute("rbitmap_note_like_check.lua", key, targetNoteId));
            assertEquals(1L, execute("rbitmap_note_unlike_check.lua", key, targetNoteId));
            assertEquals(0L, execute("rbitmap_note_unlike_check.lua", key, targetNoteId));

            assertEquals(0L, execute("rbitmap_add_note_like_and_expire.lua", key,
                    addedNoteId, EXPIRE_SECONDS));
            assertEquals(1L, execute("rbitmap_note_like_check.lua", key, addedNoteId));
        } finally {
            redisTemplate.delete(key);
        }
    }

    @Test
    void shouldRunCollectionRoaringBitmapScriptsAgainstRedis() {
        String key = "rbitmap:collect:test:" + UUID.randomUUID();
        long existingNoteId = RandomUtil.randomLong(1_000_000);
        long targetNoteId = existingNoteId + 1;
        long addedNoteId = existingNoteId + 2;

        try {
            assertEquals(-1L, execute("rbitmap_note_collect_check.lua", key, targetNoteId));
            assertEquals(0L, execute("rbitmap_batch_add_note_collect_and_expire.lua", key,
                    existingNoteId, EXPIRE_SECONDS));
            assertTrue(redisTemplate.getExpire(key, TimeUnit.SECONDS) > 0);

            assertEquals(0L, execute("rbitmap_note_collect_check.lua", key, targetNoteId));
            assertEquals(1L, execute("rbitmap_note_collect_check.lua", key, targetNoteId));
            assertEquals(1L, execute("rbitmap_note_uncollect_check.lua", key, targetNoteId));
            assertEquals(0L, execute("rbitmap_note_uncollect_check.lua", key, targetNoteId));

            assertEquals(0L, execute("rbitmap_add_note_collect_and_expire.lua", key,
                    addedNoteId, EXPIRE_SECONDS));
            assertEquals(1L, execute("rbitmap_note_collect_check.lua", key, addedNoteId));
        } finally {
            redisTemplate.delete(key);
        }
    }

    private Long execute(String scriptPath, String key, Object... args) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("/lua/" + scriptPath)));
        script.setResultType(Long.class);
        return redisTemplate.execute(script, Collections.singletonList(key), args);
    }
}
