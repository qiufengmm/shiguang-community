package com.quanxiaoha.xiaohashu.note.biz.listener;

import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteContentSagaDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.rpc.KeyValueRpcService;
import com.quanxiaoha.xiaohashu.note.biz.service.NoteContentSagaService;
import com.quanxiaoha.xiaohashu.note.biz.service.NoteOperatePersistenceService;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentCaptor.forClass;

class PublishNote2DBLocalTransactionListenerTest {

    private static final Long NOTE_ID = 991234567890111L;
    private static final Long CREATOR_ID = 992345678901122L;
    private static final String CONTENT_UUID = "11111111-1111-4111-8111-111111111111";

    private final NoteDOMapper noteDOMapper = mock(NoteDOMapper.class);
    private final RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
    private final RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
    private final KeyValueRpcService keyValueRpcService = mock(KeyValueRpcService.class);
    private final NoteOperatePersistenceService noteOperatePersistenceService = mock(NoteOperatePersistenceService.class);
    private final NoteContentSagaService noteContentSagaService = mock(NoteContentSagaService.class);
    private final PublishNote2DBLocalTransactionListener listener = new PublishNote2DBLocalTransactionListener();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(listener, "noteDOMapper", noteDOMapper);
        ReflectionTestUtils.setField(listener, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(listener, "rocketMQTemplate", rocketMQTemplate);
        ReflectionTestUtils.setField(listener, "keyValueRpcService", keyValueRpcService);
        ReflectionTestUtils.setField(listener, "noteOperatePersistenceService", noteOperatePersistenceService);
        ReflectionTestUtils.setField(listener, "noteContentSagaService", noteContentSagaService);
        when(noteContentSagaService.preparePublish(any(), anyString())).thenAnswer(invocation ->
                NoteContentSagaDO.builder()
                        .id(1L)
                        .operationId(NoteContentSagaService.publishOperationId(NOTE_ID))
                        .noteId(NOTE_ID)
                        .operationType(NoteContentSagaService.OPERATION_PUBLISH)
                        .contentUuid(CONTENT_UUID)
                        .payload("{}")
                        .status(NoteContentSagaService.STATUS_PENDING)
                        .leaseOwner(invocation.getArgument(1))
                        .leaseVersion(1L)
                        .attemptCount(1)
                        .build());
    }

    @Test
    void insertsNoteAndQueuesDurableCountEventBeforeCommitting() {
        Message<byte[]> message = transactionMessage();
        when(keyValueRpcService.saveNoteContent(CONTENT_UUID, "note body")).thenReturn(true);

        RocketMQLocalTransactionState state = listener.executeLocalTransaction(message, null);

        assertEquals(RocketMQLocalTransactionState.COMMIT, state);
        verify(keyValueRpcService).saveNoteContent(CONTENT_UUID, "note body");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                noteContentSagaService, keyValueRpcService, noteOperatePersistenceService);
        order.verify(noteContentSagaService).preparePublish(any(), anyString());
        order.verify(keyValueRpcService).saveNoteContent(CONTENT_UUID, "note body");
        order.verify(noteOperatePersistenceService).insertPublishedNote(any(NoteDO.class), any(NoteContentSagaDO.class));
        var noteCaptor = forClass(NoteDO.class);
        verify(noteOperatePersistenceService).insertPublishedNote(noteCaptor.capture(), any(NoteContentSagaDO.class));
        assertEquals(NOTE_ID, noteCaptor.getValue().getId());
        assertEquals(CREATOR_ID, noteCaptor.getValue().getCreatorId());
        assertEquals(CONTENT_UUID, noteCaptor.getValue().getContentUuid());
        verify(redisTemplate).delete("note:published:list:" + CREATOR_ID);
        verify(rocketMQTemplate).asyncSend(eq("DelayDeletePublishedNoteListRedisCacheTopic"),
                any(Message.class), any(SendCallback.class), eq(3000L), eq(1));
        verify(rocketMQTemplate, never()).asyncSend(eq("NoteOperateTopic:publishNote"),
                any(Message.class), any(SendCallback.class));
    }

    @Test
    void rollsBackAndDoesNotPublishFollowUpMessagesWhenNoteInsertFails() {
        when(keyValueRpcService.saveNoteContent(CONTENT_UUID, "note body")).thenReturn(true);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("invalid row"))
                .when(noteOperatePersistenceService).insertPublishedNote(any(NoteDO.class), any(NoteContentSagaDO.class));

        RocketMQLocalTransactionState state = listener.executeLocalTransaction(transactionMessage(), null);

        assertEquals(RocketMQLocalTransactionState.ROLLBACK, state);
        verify(rocketMQTemplate, never()).asyncSend(any(String.class), any(Message.class), any(SendCallback.class));
        verify(noteContentSagaService).markDeletePending(any(NoteContentSagaDO.class), any(Throwable.class));
        verify(keyValueRpcService, never()).deleteNoteContent(CONTENT_UUID);
    }

    @Test
    void keepsContentAndReturnsUnknownWhenInsertAndOutcomeLookupBothFail() {
        when(keyValueRpcService.saveNoteContent(CONTENT_UUID, "note body")).thenReturn(true);
        doThrow(new IllegalStateException("connection reset"))
                .when(noteOperatePersistenceService).insertPublishedNote(any(NoteDO.class), any(NoteContentSagaDO.class));
        when(noteDOMapper.selectContentUuidByNoteId(NOTE_ID))
                .thenThrow(new IllegalStateException("database unavailable"));

        RocketMQLocalTransactionState state = listener.executeLocalTransaction(transactionMessage(), null);

        assertEquals(RocketMQLocalTransactionState.UNKNOWN, state);
        verify(keyValueRpcService, never()).deleteNoteContent(CONTENT_UUID);
        verify(noteContentSagaService).scheduleRetry(any(NoteContentSagaDO.class), any(Throwable.class));
        verify(rocketMQTemplate, never()).asyncSend(any(String.class), any(Message.class), any(SendCallback.class));
    }

    @Test
    void commitsTransactionCheckWhenNoteMetadataExists() {
        when(noteDOMapper.selectContentUuidByNoteId(NOTE_ID)).thenReturn(CONTENT_UUID);

        RocketMQLocalTransactionState state = listener.checkLocalTransaction(transactionMessage());

        assertEquals(RocketMQLocalTransactionState.COMMIT, state);
    }

    @Test
    void rollsBackTransactionCheckWhenNoteMetadataIsMissing() {
        when(noteDOMapper.selectContentUuidByNoteId(NOTE_ID)).thenReturn(null);

        RocketMQLocalTransactionState state = listener.checkLocalTransaction(transactionMessage());

        assertEquals(RocketMQLocalTransactionState.UNKNOWN, state);
    }

    private Message<byte[]> transactionMessage() {
        String json = "{\"id\":" + NOTE_ID
                + ",\"title\":\"transaction note\",\"isContentEmpty\":false,\"creatorId\":" + CREATOR_ID
                + ",\"topicId\":12,\"topicName\":\"topic\",\"isTop\":false,\"type\":0"
                + ",\"imgUris\":\"https://cdn.example/cover.jpg\",\"videoUri\":null,\"visible\":0"
                + ",\"createTime\":\"2025-04-17T10:00:00\",\"updateTime\":\"2025-04-17T10:00:00\""
                + ",\"status\":1,\"contentUuid\":\"" + CONTENT_UUID + "\",\"content\":\"note body\"}";
        return MessageBuilder.withPayload(json.getBytes(StandardCharsets.UTF_8)).build();
    }
}
