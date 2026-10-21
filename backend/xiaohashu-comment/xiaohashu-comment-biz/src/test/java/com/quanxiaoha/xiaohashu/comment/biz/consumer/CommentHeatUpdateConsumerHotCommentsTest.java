package com.quanxiaoha.xiaohashu.comment.biz.consumer;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentDO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.model.bo.CommentHeatBO;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class CommentHeatUpdateConsumerHotCommentsTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void invalidatesTheHotCommentSetForTheOwningNoteAfterPersistingHeat() {
        CommentDOMapper commentDOMapper = mock(CommentDOMapper.class);
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        CommentHeatUpdateConsumer consumer = new CommentHeatUpdateConsumer();
        ReflectionTestUtils.setField(consumer, "commentDOMapper", commentDOMapper);
        ReflectionTestUtils.setField(consumer, "redisTemplate", redisTemplate);

        long commentId = 201L;
        long noteId = 901L;
        CommentDO commentDO = CommentDO.builder()
                .id(commentId)
                .noteId(noteId)
                .heat(0.0)
                .likeTotal(0L)
                .childCommentTotal(1L)
                .build();
        when(commentDOMapper.selectByCommentIds(List.of(commentId))).thenReturn(List.of(commentDO));
        when(commentDOMapper.batchUpdateHeatByCommentIds(eq(List.of(commentId)), any())).thenReturn(1);

        consumer.onMessage(JsonUtils.toJsonString(List.of(commentId)));

        ArgumentCaptor<RedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass((Class) RedisScript.class);
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(redisTemplate).execute(scriptCaptor.capture(), org.mockito.ArgumentMatchers.any(RedisSerializer.class),
                org.mockito.ArgumentMatchers.any(RedisSerializer.class), keysCaptor.capture(), argsCaptor.capture());

        assertThat(keysCaptor.getValue()).containsExactly(
                "comment:list:" + noteId,
                "comment:list:generation:" + noteId,
                "comment:list:ready:" + noteId);
        assertThat(argsCaptor.getValue()).isEqualTo("INVALIDATE");
        verify(commentDOMapper).batchUpdateHeatByCommentIds(eq(List.of(commentId)), any(List.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void invalidatesHotCommentSetWhenHeatFallsToZero() {
        CommentDOMapper commentDOMapper = mock(CommentDOMapper.class);
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        CommentHeatUpdateConsumer consumer = new CommentHeatUpdateConsumer();
        ReflectionTestUtils.setField(consumer, "commentDOMapper", commentDOMapper);
        ReflectionTestUtils.setField(consumer, "redisTemplate", redisTemplate);

        long commentId = 402L;
        long noteId = 902L;
        CommentDO commentDO = CommentDO.builder()
                .id(commentId)
                .noteId(noteId)
                .heat(0.7)
                .likeTotal(0L)
                .childCommentTotal(0L)
                .build();
        when(commentDOMapper.selectByCommentIds(List.of(commentId))).thenReturn(List.of(commentDO));
        when(commentDOMapper.batchUpdateHeatByCommentIds(eq(List.of(commentId)), any())).thenReturn(1);

        consumer.onMessage(JsonUtils.toJsonString(List.of(commentId)));

        verify(commentDOMapper).batchUpdateHeatByCommentIds(eq(List.of(commentId)), any(List.class));
        ArgumentCaptor<RedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass((Class) RedisScript.class);
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(redisTemplate).execute(scriptCaptor.capture(), org.mockito.ArgumentMatchers.any(RedisSerializer.class),
                org.mockito.ArgumentMatchers.any(RedisSerializer.class), keysCaptor.capture(), argsCaptor.capture());
        assertThat(keysCaptor.getValue()).containsExactly(
                "comment:list:" + noteId,
                "comment:list:generation:" + noteId,
                "comment:list:ready:" + noteId);
        assertThat(argsCaptor.getValue()).isEqualTo("INVALIDATE");
    }
}
