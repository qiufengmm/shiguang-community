package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CountConsumeLogMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.NoteCountDOMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.UserCountDOMapper;
import com.quanxiaoha.xiaohashu.count.biz.model.dto.AggregationCountLikeUnlikeNoteMqDTO;
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

class CountNoteLike2DBConsumerTest {

    private final NoteCountDOMapper noteCountMapper = mock(NoteCountDOMapper.class);
    private final UserCountDOMapper userCountMapper = mock(UserCountDOMapper.class);
    private final CountConsumeLogMapper logMapper = mock(CountConsumeLogMapper.class);

    private CountNoteLike2DBConsumer newConsumer() {
        CountNoteLike2DBConsumer consumer = new CountNoteLike2DBConsumer();
        ReflectionTestUtils.setField(consumer, "noteCountDOMapper", noteCountMapper);
        ReflectionTestUtils.setField(consumer, "userCountDOMapper", userCountMapper);
        ReflectionTestUtils.setField(consumer, "countConsumeLogMapper", logMapper);
        ReflectionTestUtils.setField(consumer, "transactionTemplate", passThroughTransactionTemplate());
        return consumer;
    }

    private static String bodyOf(AggregationCountLikeUnlikeNoteMqDTO... items) {
        return JsonUtils.toJsonString(List.of(items));
    }

    @Test
    void appliesNetDeltaToBothNoteAndAuthorCounters() {
        when(logMapper.insertIgnore(anyString(), anyString(), anyString())).thenReturn(1);

        newConsumer().onMessage(message("msg-1", bodyOf(
                AggregationCountLikeUnlikeNoteMqDTO.builder().noteId(73L).creatorId(19L).count(2).build())));

        verify(logMapper).insertIgnore("msg-1", MQConstants.TOPIC_COUNT_NOTE_LIKE_2_DB,
                CountNoteLike2DBConsumer.CONSUMER_GROUP);
        verify(noteCountMapper).insertOrUpdateLikeTotalByNoteId(2, 73L);
        verify(userCountMapper).insertOrUpdateLikeTotalByUserId(2, 19L);
    }

    @Test
    void redeliveryDoesNotDoubleCount() {
        when(logMapper.insertIgnore(anyString(), anyString(), anyString())).thenReturn(1, 0);
        String body = bodyOf(AggregationCountLikeUnlikeNoteMqDTO.builder().noteId(73L).creatorId(19L).count(2).build());

        CountNoteLike2DBConsumer consumer = newConsumer();
        consumer.onMessage(message("msg-1", body));
        consumer.onMessage(message("msg-1", body));

        verify(noteCountMapper).insertOrUpdateLikeTotalByNoteId(2, 73L);
        verify(userCountMapper).insertOrUpdateLikeTotalByUserId(2, 19L);
    }

    @Test
    void skipsZeroDeltaRows() {
        when(logMapper.insertIgnore(anyString(), anyString(), anyString())).thenReturn(1);

        newConsumer().onMessage(message("msg-1", bodyOf(
                AggregationCountLikeUnlikeNoteMqDTO.builder().noteId(73L).creatorId(19L).count(0).build())));

        verifyNoInteractions(noteCountMapper, userCountMapper);
    }

    @Test
    void toleratesMissingCreatorId() {
        when(logMapper.insertIgnore(anyString(), anyString(), anyString())).thenReturn(1);

        newConsumer().onMessage(message("msg-1", bodyOf(
                AggregationCountLikeUnlikeNoteMqDTO.builder().noteId(73L).count(-1).build())));

        verify(noteCountMapper).insertOrUpdateLikeTotalByNoteId(-1, 73L);
        verifyNoInteractions(userCountMapper);
    }
}
