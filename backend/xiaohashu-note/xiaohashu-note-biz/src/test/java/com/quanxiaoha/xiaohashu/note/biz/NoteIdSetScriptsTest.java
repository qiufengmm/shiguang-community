package com.quanxiaoha.xiaohashu.note.biz;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class NoteIdSetScriptsTest {

    private static final String ID_ABOVE_DOUBLE_PRECISION = "9007199254740993";
    private static final String SAME_LOW_WORD = "9007203549708289";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void storesAdjacentAndSameLowWordIdsAsDistinctDecimalStrings() {
        String suffix = UUID.randomUUID().toString();
        String setKey = "note:likes:v2:test:" + suffix;
        String readyKey = setKey + ":ready";
        String versionKey = setKey + ":version";

        try {
            assertEquals(1L, execute("note_id_set_rebuild.lua", List.of(setKey, readyKey, versionKey),
                    "0", "3600", ID_ABOVE_DOUBLE_PRECISION, "9007199254740994", SAME_LOW_WORD));

            assertEquals(1L, execute("note_id_set_check.lua", List.of(setKey, readyKey),
                    ID_ABOVE_DOUBLE_PRECISION));
            assertEquals(1L, execute("note_id_set_check.lua", List.of(setKey, readyKey), SAME_LOW_WORD));
            assertEquals(0L, execute("note_id_set_check.lua", List.of(setKey, readyKey), "4294967297"));
            assertEquals(Set.of(ID_ABOVE_DOUBLE_PRECISION, "9007199254740994", SAME_LOW_WORD),
                    stringRedisTemplate.opsForSet().members(setKey));
        } finally {
            stringRedisTemplate.delete(List.of(setKey, readyKey, versionKey));
        }
    }

    @Test
    void readyMarkerRepresentsAnInitializedEmptySetAndMutationKeepsItInitialized() {
        String suffix = UUID.randomUUID().toString();
        String setKey = "note:collects:v2:test:" + suffix;
        String readyKey = setKey + ":ready";
        String versionKey = setKey + ":version";

        try {
            assertEquals(1L, execute("note_id_set_rebuild.lua", List.of(setKey, readyKey, versionKey),
                    "0", "3600"));
            assertTrue(Boolean.TRUE.equals(stringRedisTemplate.hasKey(readyKey)));
            assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(setKey)));
            assertEquals(0L, execute("note_id_set_check.lua", List.of(setKey, readyKey), "9007199254740993"));

            assertEquals(1L, execute("note_id_set_add.lua", List.of(setKey, readyKey, versionKey),
                    ID_ABOVE_DOUBLE_PRECISION, "3600"));
            assertEquals(1L, execute("note_id_set_remove.lua", List.of(setKey, readyKey, versionKey),
                    ID_ABOVE_DOUBLE_PRECISION, "3600"));
            assertTrue(Boolean.TRUE.equals(stringRedisTemplate.hasKey(readyKey)));
            assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(setKey)));
        } finally {
            stringRedisTemplate.delete(List.of(setKey, readyKey, versionKey));
        }
    }

    private Long execute(String scriptName, List<String> keys, String... args) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("/lua/" + scriptName)));
        script.setResultType(Long.class);
        return stringRedisTemplate.execute(script, keys, (Object[]) args);
    }
}
