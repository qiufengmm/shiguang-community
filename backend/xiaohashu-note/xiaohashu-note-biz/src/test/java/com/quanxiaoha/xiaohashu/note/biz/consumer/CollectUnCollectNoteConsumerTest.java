package com.quanxiaoha.xiaohashu.note.biz.consumer;

import com.quanxiaoha.xiaohashu.note.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteCollectionDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteCollectionDOMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectUnCollectNoteConsumerTest {

    @Test
    void shouldPersistCollectionMessage() {
        NoteCollectionDOMapper mapper = mock(NoteCollectionDOMapper.class);
        RocketMQTemplate template = mock(RocketMQTemplate.class);
        CollectUnCollectNoteConsumer consumer = new CollectUnCollectNoteConsumer();
        ReflectionTestUtils.setField(consumer, "noteCollectionDOMapper", mapper);
        ReflectionTestUtils.setField(consumer, "rocketMQTemplate", template);

        ReflectionTestUtils.invokeMethod(consumer, "handleCollectNoteTagMessage",
                "{\"userId\":101,\"noteId\":201,\"type\":1,\"createTime\":\"2026-10-16T10:00:00\"}");

        verify(mapper).insertOrUpdate(any(NoteCollectionDO.class));
    }

    @Test
    void shouldPersistUncollectionMessage() {
        NoteCollectionDOMapper mapper = mock(NoteCollectionDOMapper.class);
        RocketMQTemplate template = mock(RocketMQTemplate.class);
        CollectUnCollectNoteConsumer consumer = new CollectUnCollectNoteConsumer();
        ReflectionTestUtils.setField(consumer, "noteCollectionDOMapper", mapper);
        ReflectionTestUtils.setField(consumer, "rocketMQTemplate", template);

        ReflectionTestUtils.invokeMethod(consumer, "handleUnCollectNoteTagMessage",
                "{\"userId\":101,\"noteId\":201,\"type\":0,\"createTime\":\"2026-10-16T10:00:00\"}");

        ArgumentCaptor<NoteCollectionDO> captor = ArgumentCaptor.forClass(NoteCollectionDO.class);
        verify(mapper).update2UnCollectByUserIdAndNoteId(captor.capture());
        assertEquals(101L, captor.getValue().getUserId());
        assertEquals(201L, captor.getValue().getNoteId());
        assertEquals(0, captor.getValue().getStatus());
    }

    @Test
    void shouldSynchronouslySendCountRefreshAfterCollectionPersistence() {
        NoteCollectionDOMapper mapper = mock(NoteCollectionDOMapper.class);
        RocketMQTemplate template = mock(RocketMQTemplate.class);
        CollectUnCollectNoteConsumer consumer = new CollectUnCollectNoteConsumer();
        ReflectionTestUtils.setField(consumer, "noteCollectionDOMapper", mapper);
        ReflectionTestUtils.setField(consumer, "rocketMQTemplate", template);
        when(mapper.insertOrUpdate(any())).thenReturn(1);

        ReflectionTestUtils.invokeMethod(consumer, "handleCollectNoteTagMessage",
                "{\"userId\":101,\"noteId\":201,\"type\":1}");

        verify(template).syncSend(eq(MQConstants.TOPIC_COUNT_NOTE_COLLECT), any(Message.class));
    }

    @Test
    void shouldResendIdempotentCountRefreshWhenUncollectionWasAlreadyPersisted() {
        NoteCollectionDOMapper mapper = mock(NoteCollectionDOMapper.class);
        RocketMQTemplate template = mock(RocketMQTemplate.class);
        CollectUnCollectNoteConsumer consumer = new CollectUnCollectNoteConsumer();
        ReflectionTestUtils.setField(consumer, "noteCollectionDOMapper", mapper);
        ReflectionTestUtils.setField(consumer, "rocketMQTemplate", template);
        when(mapper.update2UnCollectByUserIdAndNoteId(any())).thenReturn(0);

        ReflectionTestUtils.invokeMethod(consumer, "handleUnCollectNoteTagMessage",
                "{\"userId\":101,\"noteId\":201,\"type\":0}");

        verify(template).syncSend(eq(MQConstants.TOPIC_COUNT_NOTE_COLLECT), any(Message.class));
    }

}
