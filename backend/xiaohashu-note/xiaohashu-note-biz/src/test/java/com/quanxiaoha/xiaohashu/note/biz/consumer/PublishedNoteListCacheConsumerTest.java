package com.quanxiaoha.xiaohashu.note.biz.consumer;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.note.biz.constant.RedisKeyConstants;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PublishedNoteListCacheConsumerTest {

    private static final Long USER_ID = 712345678901234L;
    private static final Long NOTE_ID = 812345678901234L;

    @Test
    @SuppressWarnings("unchecked")
    void delayedNoteCacheDeleteRemovesDetailAndPublishedListKeys() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        DelayDeleteNoteRedisCacheConsumer consumer = new DelayDeleteNoteRedisCacheConsumer();
        ReflectionTestUtils.setField(consumer, "redisTemplate", redisTemplate);

        consumer.onMessage(JsonUtils.toJsonString(List.of(NOTE_ID, USER_ID)));

        verify(redisTemplate).delete(List.of(
                RedisKeyConstants.buildNoteDetailKey(NOTE_ID),
                RedisKeyConstants.buildPublishedNoteListKey(USER_ID)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void delayedNoteCacheDeletePropagatesRedisFailureForMessageRetry() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        when(redisTemplate.delete(anyCollection())).thenThrow(new IllegalStateException("Redis unavailable"));
        DelayDeleteNoteRedisCacheConsumer consumer = new DelayDeleteNoteRedisCacheConsumer();
        ReflectionTestUtils.setField(consumer, "redisTemplate", redisTemplate);

        assertThrows(IllegalStateException.class,
                () -> consumer.onMessage(JsonUtils.toJsonString(List.of(NOTE_ID, USER_ID))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void delayedPublishedListCacheDeleteRemovesUserKey() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        DelayDeletePublishedNoteListRedisCacheConsumer consumer = new DelayDeletePublishedNoteListRedisCacheConsumer();
        ReflectionTestUtils.setField(consumer, "redisTemplate", redisTemplate);

        consumer.onMessage(USER_ID.toString());

        verify(redisTemplate).delete(RedisKeyConstants.buildPublishedNoteListKey(USER_ID));
    }
}
