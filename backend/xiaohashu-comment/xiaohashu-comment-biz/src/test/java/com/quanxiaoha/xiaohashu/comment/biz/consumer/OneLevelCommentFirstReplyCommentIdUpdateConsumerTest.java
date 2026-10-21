package com.quanxiaoha.xiaohashu.comment.biz.consumer;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.comment.biz.enums.CommentLevelEnum;
import com.quanxiaoha.xiaohashu.comment.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentDO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.model.dto.CountPublishCommentMqDTO;
import com.quanxiaoha.xiaohashu.comment.biz.service.CommentService;
import org.junit.jupiter.api.Test;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OneLevelCommentFirstReplyCommentIdUpdateConsumerTest {

    @Test
    void extractsDistinctParentsFromLevelTwoCommentMessages() {
        String body = JsonUtils.toJsonString(List.of(
                CountPublishCommentMqDTO.builder().level(CommentLevelEnum.ONE.getCode()).parentId(55L).build(),
                CountPublishCommentMqDTO.builder().level(CommentLevelEnum.TWO.getCode()).parentId(88L).build(),
                CountPublishCommentMqDTO.builder().level(CommentLevelEnum.TWO.getCode()).parentId(88L).build(),
                CountPublishCommentMqDTO.builder().level(CommentLevelEnum.TWO.getCode()).parentId(99L).build()));

        assertThat(OneLevelCommentFirstReplyCommentIdUpdateConsumer.extractParentIds(List.of(body)))
                .containsExactly(88L, 99L);
    }

    @Test
    void clearsParentPreviewCachesAfterFirstReplyPointerIsUpdated() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        CommentDOMapper commentDOMapper = mock(CommentDOMapper.class);
        CommentService commentService = mock(CommentService.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        OneLevelCommentFirstReplyCommentIdUpdateConsumer consumer =
                new OneLevelCommentFirstReplyCommentIdUpdateConsumer();
        ReflectionTestUtils.setField(consumer, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(consumer, "commentDOMapper", commentDOMapper);
        ReflectionTestUtils.setField(consumer, "commentService", commentService);
        ReflectionTestUtils.setField(consumer, "rocketMQTemplate", rocketMQTemplate);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(anyList())).thenReturn(java.util.Collections.singletonList(null));
        when(commentDOMapper.selectByCommentIds(List.of(900L))).thenReturn(List.of(CommentDO.builder()
                .id(900L).firstReplyCommentId(0L).build()));
        when(commentDOMapper.selectEarliestByParentId(900L)).thenReturn(CommentDO.builder().id(901L).build());
        when(commentDOMapper.updateFirstReplyCommentIdByPrimaryKey(901L, 900L)).thenReturn(1);
        SendResult sendResult = mock(SendResult.class);
        when(sendResult.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSend(anyString(), anyString())).thenReturn(sendResult);

        consumer.onMessage(JsonUtils.toJsonString(List.of(CountPublishCommentMqDTO.builder()
                .level(CommentLevelEnum.TWO.getCode()).parentId(900L).build())));

        var order = inOrder(commentDOMapper, redisTemplate, commentService, rocketMQTemplate);
        order.verify(commentDOMapper).updateFirstReplyCommentIdByPrimaryKey(901L, 900L);
        order.verify(redisTemplate).delete(RedisKeyConstants.buildCommentDetailKey(900L));
        order.verify(commentService).deleteCommentLocalCache(900L);
        order.verify(rocketMQTemplate).syncSend(com.quanxiaoha.xiaohashu.comment.biz.constant.MQConstants
                .TOPIC_DELETE_COMMENT_LOCAL_CACHE, "900");
    }

    @Test
    void updatesSqlPointerEvenWhenRedisStillHasAStaleFirstReplyMarker() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        CommentDOMapper commentDOMapper = mock(CommentDOMapper.class);
        CommentService commentService = mock(CommentService.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        OneLevelCommentFirstReplyCommentIdUpdateConsumer consumer =
                new OneLevelCommentFirstReplyCommentIdUpdateConsumer();
        ReflectionTestUtils.setField(consumer, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(consumer, "commentDOMapper", commentDOMapper);
        ReflectionTestUtils.setField(consumer, "commentService", commentService);
        ReflectionTestUtils.setField(consumer, "rocketMQTemplate", rocketMQTemplate);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(anyList())).thenReturn(List.of(1));
        when(commentDOMapper.selectByCommentIds(List.of(900L))).thenReturn(List.of(CommentDO.builder()
                .id(900L).firstReplyCommentId(0L).build()));
        when(commentDOMapper.selectEarliestByParentId(900L)).thenReturn(CommentDO.builder().id(902L).build());
        when(commentDOMapper.updateFirstReplyCommentIdByPrimaryKey(902L, 900L)).thenReturn(1);
        SendResult sendResult = mock(SendResult.class);
        when(sendResult.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSend(anyString(), anyString())).thenReturn(sendResult);

        consumer.onMessage(JsonUtils.toJsonString(List.of(CountPublishCommentMqDTO.builder()
                .level(CommentLevelEnum.TWO.getCode()).parentId(900L).build())));

        verify(commentDOMapper).updateFirstReplyCommentIdByPrimaryKey(902L, 900L);
    }
}
