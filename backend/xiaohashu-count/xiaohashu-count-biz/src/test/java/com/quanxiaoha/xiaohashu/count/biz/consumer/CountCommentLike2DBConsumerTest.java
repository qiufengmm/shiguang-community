package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CommentDOMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CountConsumeLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static com.quanxiaoha.xiaohashu.count.biz.consumer.CountConsumerTestSupport.message;
import static com.quanxiaoha.xiaohashu.count.biz.consumer.CountConsumerTestSupport.passThroughTransactionTemplate;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CountCommentLike2DBConsumerTest {

    private final CommentDOMapper mapper = mock(CommentDOMapper.class);
    private final CountConsumeLogMapper logMapper = mock(CountConsumeLogMapper.class);

    private CountCommentLike2DBConsumer newConsumer() {
        CountCommentLike2DBConsumer consumer = new CountCommentLike2DBConsumer();
        ReflectionTestUtils.setField(consumer, "commentDOMapper", mapper);
        ReflectionTestUtils.setField(consumer, "countConsumeLogMapper", logMapper);
        ReflectionTestUtils.setField(consumer, "transactionTemplate", passThroughTransactionTemplate());
        return consumer;
    }

    @Test
    void appliesAggregatedCommentLikeDeltaToMapper() {
        when(logMapper.insertIgnore(anyString(), anyString(), anyString())).thenReturn(1);

        newConsumer().onMessage(message("msg-1", "[{\"commentId\":901234567890123,\"count\":-1}]"));

        verify(logMapper).insertIgnore("msg-1", MQConstants.TOPIC_COUNT_COMMENT_LIKE_2_DB,
                CountCommentLike2DBConsumer.CONSUMER_GROUP);
        verify(mapper).updateLikeTotalByCommentId(-1, 901234567890123L);
    }

    @Test
    void redeliveryDoesNotDoubleCount() {
        when(logMapper.insertIgnore(anyString(), anyString(), anyString())).thenReturn(1, 0);
        String body = "[{\"commentId\":901234567890123,\"count\":-1}]";

        CountCommentLike2DBConsumer consumer = newConsumer();
        consumer.onMessage(message("msg-1", body));
        consumer.onMessage(message("msg-1", body));

        verify(mapper).updateLikeTotalByCommentId(-1, 901234567890123L);
    }

    @Test
    void ignoresUnparsableBodyWithoutTouchingTheDatabase() {
        newConsumer().onMessage(message("msg-1", "not-json"));

        verifyNoInteractions(mapper, logMapper);
    }
}
