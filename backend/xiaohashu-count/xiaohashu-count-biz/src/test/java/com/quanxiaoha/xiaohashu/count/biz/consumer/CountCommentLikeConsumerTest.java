package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.count.biz.domain.dataobject.CommentDO;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CommentCountEventMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CommentDOMapper;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CountCommentLikeConsumerTest {
    @Test
    void persistsLikeCountWithStableMessageIdThenInvalidatesAndRecalculatesHeat() {
        Fixture fixture = new Fixture(1);

        fixture.consumer.processEvent("rocket-msg-901", "{\"userId\":41,\"commentId\":901,\"type\":1}");

        verify(fixture.eventMapper).insertIfAbsent("comment-like:rocket-msg-901", "COMMENT_LIKE_TOTAL",
                901L, 902L, 903L, 1);
        verify(fixture.commentMapper).updateLikeTotalByCommentId(1, 901L);
        verify(fixture.redisTemplate).delete("count:comment:901");
        verify(fixture.rocketMQTemplate).syncSend(eq(MQConstants.TOPIC_COMMENT_HEAT_UPDATE),
                any(org.springframework.messaging.Message.class));
    }

    @Test
    void duplicateDeliveryDoesNotIncrementAgainAndStillRetriesCacheAndHeatFollowups() {
        Fixture fixture = new Fixture(0);

        fixture.consumer.processEvent("rocket-msg-901", "{\"userId\":41,\"commentId\":901,\"type\":1}");

        verify(fixture.commentMapper, never()).updateLikeTotalByCommentId(any(), any());
        verify(fixture.redisTemplate).delete("count:comment:901");
        verify(fixture.rocketMQTemplate).syncSend(eq(MQConstants.TOPIC_COMMENT_HEAT_UPDATE),
                any(org.springframework.messaging.Message.class));
    }

    @Test
    void repeatedUnlikeOnlyDecrementsOnceAndRelikeCanIncrementAgain() {
        Fixture fixture = new Fixture(1, 1);
        String unlike = "{\"userId\":41,\"commentId\":901,\"type\":0}";
        String like = "{\"userId\":41,\"commentId\":901,\"type\":1}";

        fixture.consumer.processEvent("rocket-unlike-1", unlike);
        fixture.consumer.processEvent("rocket-unlike-2", unlike);
        fixture.consumer.processEvent("rocket-relike-1", like);

        verify(fixture.commentMapper, org.mockito.Mockito.times(1)).updateLikeTotalByCommentId(-1, 901L);
        verify(fixture.commentMapper, org.mockito.Mockito.times(1)).updateLikeTotalByCommentId(1, 901L);
    }

    private static final class Fixture {
        final CommentDOMapper commentMapper = mock(CommentDOMapper.class);
        final CommentCountEventMapper eventMapper = mock(CommentCountEventMapper.class);
        final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        @SuppressWarnings("unchecked")
        final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        final RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        final CountCommentLikeConsumer consumer = new CountCommentLikeConsumer();
        private int currentLikeState;

        Fixture(int inserted) {
            this(inserted, 0);
        }

        Fixture(int inserted, int initialLikeState) {
            currentLikeState = initialLikeState;
            ReflectionTestUtils.setField(consumer, "commentDOMapper", commentMapper);
            ReflectionTestUtils.setField(consumer, "commentCountEventMapper", eventMapper);
            ReflectionTestUtils.setField(consumer, "transactionTemplate", transactionTemplate);
            ReflectionTestUtils.setField(consumer, "redisTemplate", redisTemplate);
            ReflectionTestUtils.setField(consumer, "rocketMQTemplate", rocketMQTemplate);
            when(eventMapper.selectTombstoneForUpdate(901L)).thenReturn(null);
            when(eventMapper.insertIfAbsent(anyString(), anyString(), any(), any(), any(), anyInt()))
                    .thenAnswer(invocation -> {
                        String eventId = invocation.getArgument(0);
                        return eventId.startsWith("comment-like-state:") ? 0 : inserted;
                    });
            when(eventMapper.selectAppliedDeltaForUpdate(anyString(), anyString()))
                    .thenAnswer(invocation -> currentLikeState);
            when(eventMapper.updateAppliedDelta(anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
                currentLikeState = invocation.getArgument(2);
                return 1;
            });
            when(commentMapper.selectByPrimaryKey(901L)).thenReturn(CommentDO.builder()
                    .id(901L).noteId(902L).parentId(903L).build());
            when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
                TransactionCallback<?> callback = invocation.getArgument(0);
                return callback.doInTransaction(mock(TransactionStatus.class));
            });
            SendResult sendResult = mock(SendResult.class);
            when(sendResult.getSendStatus()).thenReturn(SendStatus.SEND_OK);
            when(rocketMQTemplate.syncSend(any(String.class), any(org.springframework.messaging.Message.class)))
                    .thenReturn(sendResult);
        }
    }
}
