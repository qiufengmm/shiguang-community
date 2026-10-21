package com.quanxiaoha.xiaohashu.comment.biz.consumer;

import com.quanxiaoha.xiaohashu.comment.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.comment.biz.enums.CommentLevelEnum;
import com.quanxiaoha.xiaohashu.comment.biz.model.bo.CommentBO;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

class Comment2DBConsumerHotCommentsTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void invalidatesHotCommentSnapshotForANewOneLevelComment() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        Comment2DBConsumer consumer = new Comment2DBConsumer();
        ReflectionTestUtils.setField(consumer, "redisTemplate", redisTemplate);

        List<CommentBO> comments = List.of(
                CommentBO.builder().id(101L).noteId(900L).level(CommentLevelEnum.ONE.getCode()).build(),
                CommentBO.builder().id(102L).noteId(900L).level(CommentLevelEnum.ONE.getCode()).build(),
                CommentBO.builder().id(103L).noteId(900L).level(CommentLevelEnum.TWO.getCode()).build());

        ReflectionTestUtils.invokeMethod(consumer, "syncOneLevelComment2RedisZSet", comments);

        ArgumentCaptor<RedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass((Class) RedisScript.class);
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(redisTemplate).execute(scriptCaptor.capture(), org.mockito.ArgumentMatchers.any(RedisSerializer.class),
                org.mockito.ArgumentMatchers.any(RedisSerializer.class), keysCaptor.capture(), argsCaptor.capture());

        assertThat(keysCaptor.getValue()).containsExactly(
                RedisKeyConstants.buildCommentListKey(900L),
                RedisKeyConstants.buildCommentListGenerationKey(900L),
                RedisKeyConstants.buildCommentListReadyKey(900L));
        assertThat(argsCaptor.getValue()).isEqualTo("INVALIDATE");
    }
}
