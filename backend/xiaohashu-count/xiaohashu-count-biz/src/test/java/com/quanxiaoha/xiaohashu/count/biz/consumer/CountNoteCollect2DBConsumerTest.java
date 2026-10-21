package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CountConsumeLogMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.NoteCountDOMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.UserCountDOMapper;
import com.quanxiaoha.xiaohashu.count.biz.model.dto.AggregationCountCollectUnCollectNoteMqDTO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static com.quanxiaoha.xiaohashu.count.biz.consumer.CountConsumerTestSupport.message;
import static com.quanxiaoha.xiaohashu.count.biz.consumer.CountConsumerTestSupport.passThroughTransactionTemplate;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CountNoteCollect2DBConsumerTest {

    private final NoteCountDOMapper noteCountMapper = mock(NoteCountDOMapper.class);
    private final UserCountDOMapper userCountMapper = mock(UserCountDOMapper.class);
    private final CountConsumeLogMapper logMapper = mock(CountConsumeLogMapper.class);

    private CountNoteCollect2DBConsumer newConsumer() {
        CountNoteCollect2DBConsumer consumer = new CountNoteCollect2DBConsumer();
        ReflectionTestUtils.setField(consumer, "noteCountDOMapper", noteCountMapper);
        ReflectionTestUtils.setField(consumer, "userCountDOMapper", userCountMapper);
        ReflectionTestUtils.setField(consumer, "countConsumeLogMapper", logMapper);
        ReflectionTestUtils.setField(consumer, "transactionTemplate", passThroughTransactionTemplate());
        return consumer;
    }

    private static String bodyOf(AggregationCountCollectUnCollectNoteMqDTO... items) {
        return JsonUtils.toJsonString(List.of(items));
    }

    @Test
    void appliesNetDeltaToBothNoteAndAuthorCounters() {
        when(logMapper.insertIgnore(anyString(), anyString(), anyString())).thenReturn(1);

        newConsumer().onMessage(message("msg-1", bodyOf(
                AggregationCountCollectUnCollectNoteMqDTO.builder().noteId(73L).creatorId(19L).count(4).build())));

        verify(logMapper).insertIgnore("msg-1", MQConstants.TOPIC_COUNT_NOTE_COLLECT_2_DB,
                CountNoteCollect2DBConsumer.CONSUMER_GROUP);
        verify(noteCountMapper).insertOrUpdateCollectTotalByNoteId(4, 73L);
        verify(userCountMapper).insertOrUpdateCollectTotalByUserId(4, 19L);
    }

    @Test
    void redeliveryDoesNotDoubleCount() {
        when(logMapper.insertIgnore(anyString(), anyString(), anyString())).thenReturn(1, 0);
        String body = bodyOf(
                AggregationCountCollectUnCollectNoteMqDTO.builder().noteId(73L).creatorId(19L).count(4).build());

        CountNoteCollect2DBConsumer consumer = newConsumer();
        consumer.onMessage(message("msg-1", body));
        consumer.onMessage(message("msg-1", body));

        verify(noteCountMapper).insertOrUpdateCollectTotalByNoteId(4, 73L);
        verify(userCountMapper).insertOrUpdateCollectTotalByUserId(4, 19L);
    }

    @Test
    void skipsZeroDeltaRows() {
        when(logMapper.insertIgnore(anyString(), anyString(), anyString())).thenReturn(1);

        newConsumer().onMessage(message("msg-1", bodyOf(
                AggregationCountCollectUnCollectNoteMqDTO.builder().noteId(73L).creatorId(19L).count(0).build())));

        verifyNoInteractions(noteCountMapper, userCountMapper);
    }
}
