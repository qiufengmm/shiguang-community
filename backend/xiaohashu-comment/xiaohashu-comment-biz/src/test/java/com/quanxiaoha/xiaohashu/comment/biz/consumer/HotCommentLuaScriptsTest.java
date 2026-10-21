package com.quanxiaoha.xiaohashu.comment.biz.consumer;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class HotCommentLuaScriptsTest {

    @Test
    void hotCommentUpdatesValidateAndCompareNumericScoresBeforeChangingTheSortedSet() throws Exception {
        String script = readScript("lua/update_hot_comments.lua");

        assertThat(script).contains("tonumber(", "INVALIDATE", "INCR", "ZREMRANGEBYRANK");
        assertThat(script.indexOf("invalid score"))
                .isGreaterThanOrEqualTo(0)
                .isLessThan(script.indexOf("redis.call(\"ZADD\""));
    }

    @Test
    void hotCommentSnapshotReplacementChecksGenerationAndSupportsAnEmptyReadySnapshot() throws Exception {
        String script = readScript("lua/replace_hot_comments.lua");

        assertThat(script).contains("expectedGeneration", "currentGeneration", "SET", "ZCARD");
        assertThat(script).contains("readyKey");
        assertThat(script).contains("lockKey", "lockToken", "ARGV[3]");
    }

    @Test
    void hotCommentPageReadChecksGenerationAndReadsThePageAtomically() throws Exception {
        String script = readScript("lua/read_hot_comment_page.lua");

        assertThat(script).contains("KEYS[2]", "KEYS[3]", "ZREVRANGE", "currentGeneration");
    }

    @Test
    void rebuildLockReleaseComparesTheOwnerTokenBeforeDeletingTheLock() throws Exception {
        String script = readScript("lua/release_hot_comment_rebuild_lock.lua");

        assertThat(script).contains("GET", "KEYS[1]", "ARGV[1]", "DEL", "return 0");
        assertThat(script.indexOf("== ARGV[1]")).isLessThan(script.indexOf("redis.call(\"DEL\""));
    }

    private static String readScript(String resourcePath) throws IOException {
        var resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            return "";
        }
        try (var input = resource.getInputStream()) {
            return StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        }
    }
}
