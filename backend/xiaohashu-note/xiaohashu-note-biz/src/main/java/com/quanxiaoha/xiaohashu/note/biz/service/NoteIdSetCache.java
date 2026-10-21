package com.quanxiaoha.xiaohashu.note.biz.service;

import jakarta.annotation.Resource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Redis Set 缓存笔记关系的完整十进制 Long ID。 */
@Component
public class NoteIdSetCache {

    private static final DefaultRedisScript<Long> CHECK_SCRIPT = loadScript("note_id_set_check.lua", Long.class);
    private static final DefaultRedisScript<List> CHECK_BATCH_SCRIPT = loadScript("note_id_set_check_batch.lua", List.class);
    private static final DefaultRedisScript<Long> ADD_SCRIPT = loadScript("note_id_set_add.lua", Long.class);
    private static final DefaultRedisScript<Long> REMOVE_SCRIPT = loadScript("note_id_set_remove.lua", Long.class);
    private static final DefaultRedisScript<Long> REBUILD_SCRIPT = loadScript("note_id_set_rebuild.lua", Long.class);
    private static final DefaultRedisScript<Long> INVALIDATE_SCRIPT = loadScript("note_id_set_invalidate.lua", Long.class);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public Long contains(String setKey, String readyKey, Long noteId) {
        return stringRedisTemplate.execute(CHECK_SCRIPT, List.of(setKey, readyKey), Long.toString(noteId));
    }

    public List<Long> containsAll(String setKey, String readyKey, Collection<Long> noteIds) {
        Object[] args = noteIds.stream().map(String::valueOf).toArray();
        List<?> result = stringRedisTemplate.execute(CHECK_BATCH_SCRIPT, List.of(setKey, readyKey), args);
        if (Objects.isNull(result)) {
            return List.of(-1L);
        }
        List<Long> normalized = new ArrayList<>(result.size());
        for (Object value : result) {
            normalized.add(value instanceof Number number ? number.longValue() : Long.parseLong(value.toString()));
        }
        return normalized;
    }

    public Long addIfReady(String setKey, String readyKey, String versionKey, Long noteId, long ttlSeconds) {
        return stringRedisTemplate.execute(ADD_SCRIPT, List.of(setKey, readyKey, versionKey),
                Long.toString(noteId), Long.toString(ttlSeconds));
    }

    public Long removeIfReady(String setKey, String readyKey, String versionKey, Long noteId, long ttlSeconds) {
        return stringRedisTemplate.execute(REMOVE_SCRIPT, List.of(setKey, readyKey, versionKey),
                Long.toString(noteId), Long.toString(ttlSeconds));
    }

    public String currentVersion(String versionKey) {
        String version = stringRedisTemplate.opsForValue().get(versionKey);
        return Objects.isNull(version) ? "0" : version;
    }

    /**
     * 仅当回源期间没有其他缓存变更时，原子替换完整快照并置 ready。
     */
    public boolean rebuildIfVersionMatches(String setKey, String readyKey, String versionKey,
                                           String expectedVersion, Collection<Long> noteIds, long ttlSeconds) {
        Set<String> distinctIds = new LinkedHashSet<>();
        noteIds.stream().filter(Objects::nonNull).map(String::valueOf).forEach(distinctIds::add);
        Object[] args = new Object[distinctIds.size() + 2];
        args[0] = expectedVersion;
        args[1] = Long.toString(ttlSeconds);
        int index = 2;
        for (String noteId : distinctIds) {
            args[index++] = noteId;
        }
        Long result = stringRedisTemplate.execute(REBUILD_SCRIPT, List.of(setKey, readyKey, versionKey), args);
        return Objects.equals(result, 1L);
    }

    /** Invalidates a relation snapshot and advances its version to fence in-flight rebuilds. */
    public void invalidate(String setKey, String readyKey, String versionKey, long ttlSeconds) {
        stringRedisTemplate.execute(INVALIDATE_SCRIPT, List.of(setKey, readyKey, versionKey),
                Long.toString(ttlSeconds));
    }

    private static <T> DefaultRedisScript<T> loadScript(String scriptName, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("/lua/" + scriptName)));
        script.setResultType(resultType);
        return script;
    }
}
