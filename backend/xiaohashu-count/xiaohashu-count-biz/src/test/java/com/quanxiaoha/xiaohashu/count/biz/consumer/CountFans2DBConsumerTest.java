package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CountConsumeLogMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.UserCountDOMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static com.quanxiaoha.xiaohashu.count.biz.consumer.CountConsumerTestSupport.message;
import static com.quanxiaoha.xiaohashu.count.biz.consumer.CountConsumerTestSupport.passThroughTransactionTemplate;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CountFans2DBConsumerTest {

    private CountFans2DBConsumer newConsumer(UserCountDOMapper mapper, CountConsumeLogMapper logMapper) {
        CountFans2DBConsumer consumer = new CountFans2DBConsumer();
        ReflectionTestUtils.setField(consumer, "userCountDOMapper", mapper);
        ReflectionTestUtils.setField(consumer, "countConsumeLogMapper", logMapper);
        ReflectionTestUtils.setField(consumer, "transactionTemplate", passThroughTransactionTemplate());
        return consumer;
    }

    @Test
    void appliesAggregatedDeltaPerUser() {
        UserCountDOMapper mapper = mock(UserCountDOMapper.class);
        CountConsumeLogMapper logMapper = mock(CountConsumeLogMapper.class);
        when(logMapper.insertIgnore(anyString(), anyString(), anyString())).thenReturn(1);

        newConsumer(mapper, logMapper).onMessage(message("msg-1", "{\"29\":3,\"30\":-2}"));

        verify(logMapper).insertIgnore("msg-1", MQConstants.TOPIC_COUNT_FANS_2_DB, CountFans2DBConsumer.CONSUMER_GROUP);
        verify(mapper).insertOrUpdateFansTotalByUserId(3, 29L);
        verify(mapper).insertOrUpdateFansTotalByUserId(-2, 30L);
    }

    @Test
    void skipsWholeBatchWhenMessageWasAlreadyApplied() {
        UserCountDOMapper mapper = mock(UserCountDOMapper.class);
        CountConsumeLogMapper logMapper = mock(CountConsumeLogMapper.class);
        // 主键冲突：这条消息之前已经落过库
        when(logMapper.insertIgnore(anyString(), anyString(), anyString())).thenReturn(0);

        newConsumer(mapper, logMapper).onMessage(message("msg-dup", "{\"29\":3}"));

        verifyNoInteractions(mapper);
    }

    @Test
    void redeliveryDoesNotDoubleCount() {
        UserCountDOMapper mapper = mock(UserCountDOMapper.class);
        CountConsumeLogMapper logMapper = mock(CountConsumeLogMapper.class);
        when(logMapper.insertIgnore(anyString(), anyString(), anyString())).thenReturn(1, 0);

        CountFans2DBConsumer consumer = newConsumer(mapper, logMapper);
        consumer.onMessage(message("msg-1", "{\"29\":3}"));
        consumer.onMessage(message("msg-1", "{\"29\":3}"));

        verify(mapper).insertOrUpdateFansTotalByUserId(3, 29L);
    }

    @Test
    void skipsZeroDeltaUser() {
        UserCountDOMapper mapper = mock(UserCountDOMapper.class);
        CountConsumeLogMapper logMapper = mock(CountConsumeLogMapper.class);
        when(logMapper.insertIgnore(anyString(), anyString(), anyString())).thenReturn(1);

        newConsumer(mapper, logMapper).onMessage(message("msg-1", "{\"29\":0}"));

        verify(mapper, never()).insertOrUpdateFansTotalByUserId(any(), any());
    }
}
