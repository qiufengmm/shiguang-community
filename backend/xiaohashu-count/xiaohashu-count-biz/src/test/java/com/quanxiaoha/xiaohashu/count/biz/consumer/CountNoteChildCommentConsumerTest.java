package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CommentDOMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CommentCountEventMapper;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;

class CountNoteChildCommentConsumerTest {

    @Test
    void countsOnlySecondLevelCommentsByParentId() {
        List<String> messageBodies = List.of(
                "[{\"noteId\":1001,\"commentId\":11,\"level\":1,\"parentId\":1001},"
                        + "{\"noteId\":1001,\"commentId\":12,\"level\":2,\"parentId\":11},"
                        + "{\"noteId\":1001,\"commentId\":13,\"level\":2,\"parentId\":11}]",
                "[{\"noteId\":1001,\"commentId\":14,\"level\":2,\"parentId\":12}]"
        );

        Map<Long, Integer> counts = CountNoteChildCommentConsumer.aggregateChildCommentCounts(messageBodies);

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(11L, 2, 12L, 1));
    }

    @Test
    void appliesChildCounterTransactionAndInvalidatesCache() {
        CommentDOMapper commentDOMapper = mock(CommentDOMapper.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        CommentCountEventMapper eventMapper = mock(CommentCountEventMapper.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        SendResult sendResult = mock(SendResult.class);
        when(sendResult.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        CountNoteChildCommentConsumer consumer = new CountNoteChildCommentConsumer();
        assertThat(org.springframework.util.ReflectionUtils.findField(
                CountNoteChildCommentConsumer.class, "redisTemplate"))
                .as("the consumer must read the cached comment counter")
                .isNotNull();
        ReflectionTestUtils.setField(consumer, "commentDOMapper", commentDOMapper);
        ReflectionTestUtils.setField(consumer, "rocketMQTemplate", rocketMQTemplate);
        ReflectionTestUtils.setField(consumer, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(consumer, "commentCountEventMapper", eventMapper);
        ReflectionTestUtils.setField(consumer, "transactionTemplate", transactionTemplate);

        when(eventMapper.selectTombstoneForUpdate(any())).thenReturn(null);
        when(eventMapper.insertIfAbsent(any(), any(), any(), any(), any(), any(Integer.class))).thenReturn(1);
        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        when(rocketMQTemplate.syncSend(any(String.class), any(org.springframework.messaging.Message.class))).thenReturn(sendResult);

        consumer.onMessage("[{\"noteId\":1001,\"commentId\":12,\"level\":2,\"parentId\":11}]");

        verify(eventMapper).insertIfAbsent("comment-count-publish:12", "CHILD_COMMENT_TOTAL", 12L, 1001L, 11L, 1);
        verify(commentDOMapper).updateChildCommentTotal(11L, 1);
        verify(redisTemplate).delete("count:comment:11");
        verify(rocketMQTemplate).syncSend(eq(com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants.TOPIC_COMMENT_HEAT_UPDATE),
                any(org.springframework.messaging.Message.class));
    }

    @Test
    void redeliveryDoesNotIncrementChildCountAgain() {
        CommentDOMapper commentDOMapper = mock(CommentDOMapper.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        CommentCountEventMapper eventMapper = mock(CommentCountEventMapper.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        SendResult sendResult = mock(SendResult.class);
        when(sendResult.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        CountNoteChildCommentConsumer consumer = new CountNoteChildCommentConsumer();
        ReflectionTestUtils.setField(consumer, "commentDOMapper", commentDOMapper);
        ReflectionTestUtils.setField(consumer, "rocketMQTemplate", rocketMQTemplate);
        ReflectionTestUtils.setField(consumer, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(consumer, "commentCountEventMapper", eventMapper);
        ReflectionTestUtils.setField(consumer, "transactionTemplate", transactionTemplate);
        when(eventMapper.selectTombstoneForUpdate(any())).thenReturn(null);
        when(eventMapper.insertIfAbsent(any(), any(), any(), any(), any(), any(Integer.class))).thenReturn(0);
        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        when(rocketMQTemplate.syncSend(any(String.class), any(org.springframework.messaging.Message.class))).thenReturn(sendResult);

        consumer.onMessage("[{\"noteId\":1001,\"commentId\":12,\"level\":2,\"parentId\":11}]");

        verify(commentDOMapper, never()).updateChildCommentTotal(any(Long.class), any(Integer.class));
        verify(redisTemplate).delete("count:comment:11");
    }
}
