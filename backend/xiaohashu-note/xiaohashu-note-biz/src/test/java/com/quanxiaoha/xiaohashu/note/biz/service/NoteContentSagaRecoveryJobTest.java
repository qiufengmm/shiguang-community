package com.quanxiaoha.xiaohashu.note.biz.service;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteContentSagaDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.enums.NoteStatusEnum;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.PublishNoteDTO;
import com.quanxiaoha.xiaohashu.note.biz.rpc.KeyValueRpcService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NoteContentSagaRecoveryJobTest {

    private static final Long NOTE_ID = 991234567890111L;
    private static final String CONTENT_UUID = "11111111-1111-4111-8111-111111111111";

    private final NoteContentSagaService sagaService = mock(NoteContentSagaService.class);
    private final NoteDOMapper noteDOMapper = mock(NoteDOMapper.class);
    private final NoteOperatePersistenceService persistenceService = mock(NoteOperatePersistenceService.class);
    private final KeyValueRpcService keyValueRpcService = mock(KeyValueRpcService.class);
    private final NoteContentSagaRecoveryJob job = new NoteContentSagaRecoveryJob();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(job, "sagaService", sagaService);
        ReflectionTestUtils.setField(job, "noteDOMapper", noteDOMapper);
        ReflectionTestUtils.setField(job, "persistenceService", persistenceService);
        ReflectionTestUtils.setField(job, "keyValueRpcService", keyValueRpcService);
    }

    @Test
    void recoversMissingMetadataBySavingBodyThenPersistingNoteAndSagaTogether() {
        NoteContentSagaDO saga = pendingSaga();
        when(noteDOMapper.selectByPrimaryKey(NOTE_ID)).thenReturn(null);
        when(keyValueRpcService.saveNoteContent(CONTENT_UUID, "random saga recovery body")).thenReturn(true);

        job.recover(saga);

        var order = org.mockito.Mockito.inOrder(keyValueRpcService, persistenceService);
        order.verify(keyValueRpcService).saveNoteContent(CONTENT_UUID, "random saga recovery body");
        order.verify(persistenceService).insertPublishedNote(any(NoteDO.class), eq(saga));
        verify(persistenceService, never()).markNoteContentSagaCompleted(any(NoteContentSagaDO.class));
    }

    @Test
    void repairsCommittedMetadataWithoutReinsertingIt() {
        NoteContentSagaDO saga = pendingSaga();
        when(noteDOMapper.selectByPrimaryKey(NOTE_ID))
                .thenReturn(NoteDO.builder().id(NOTE_ID).contentUuid(CONTENT_UUID).status(1).build());
        when(keyValueRpcService.saveNoteContent(CONTENT_UUID, "random saga recovery body")).thenReturn(true);

        job.recover(saga);

        verify(keyValueRpcService).saveNoteContent(CONTENT_UUID, "random saga recovery body");
        verify(persistenceService).markNoteContentSagaCompleted(saga);
        verify(persistenceService, never()).insertPublishedNote(any(NoteDO.class), any(NoteContentSagaDO.class));
    }

    @Test
    void cleansBodyWhenTheNoteMetadataUsesAnotherUuid() {
        NoteContentSagaDO saga = pendingSaga();
        when(noteDOMapper.selectByPrimaryKey(NOTE_ID))
                .thenReturn(NoteDO.builder().id(NOTE_ID).contentUuid("22222222-2222-4222-8222-222222222222")
                        .status(1).build());

        job.recover(saga);

        verify(sagaService).markCompensationPending(eq(saga), eq(CONTENT_UUID), any(IllegalStateException.class));
        verify(keyValueRpcService, never()).saveNoteContent(anyString(), anyString());
    }

    @Test
    void retriesCleanupUntilCassandraConfirmsDeletion() {
        NoteContentSagaDO saga = pendingSaga();
        saga.setStatus(NoteContentSagaService.STATUS_DELETE_PENDING);
        when(keyValueRpcService.deleteNoteContent(CONTENT_UUID)).thenReturn(true);

        job.recover(saga);

        verify(keyValueRpcService).deleteNoteContent(CONTENT_UUID);
        verify(persistenceService).markNoteContentSagaCompleted(saga);
    }

    @Test
    void compensatesUncommittedUpdateTargetWithoutChangingTheCurrentBody() {
        NoteContentSagaDO saga = pendingSaga();
        saga.setOperationType(NoteContentSagaService.OPERATION_UPDATE);
        saga.setPayload(null);
        when(noteDOMapper.selectByPrimaryKey(NOTE_ID))
                .thenReturn(NoteDO.builder().id(NOTE_ID).contentUuid("22222222-2222-4222-8222-222222222222")
                        .isContentEmpty(false).status(1).build());

        job.recover(saga);

        verify(sagaService).markCompensationPending(eq(saga), eq(CONTENT_UUID), any(IllegalStateException.class));
        verify(keyValueRpcService, never()).saveNoteContent(anyString(), anyString());
    }

    @Test
    void retriesDeleteAfterCommittedClear() {
        NoteContentSagaDO saga = pendingSaga();
        saga.setOperationType(NoteContentSagaService.OPERATION_CLEAR);
        saga.setPayload(null);
        when(noteDOMapper.selectByPrimaryKeyIncludingDeleted(NOTE_ID)).thenReturn(NoteDO.builder()
                .id(NOTE_ID).contentUuid(null).isContentEmpty(true).status(1).build());

        job.recover(saga);

        verify(persistenceService).markOldNoteContentDeletePending(saga, CONTENT_UUID);
    }

    @Test
    void marksCompensationRolledBackOnlyAfterCassandraConfirmsDelete() {
        NoteContentSagaDO saga = pendingSaga();
        saga.setStatus(NoteContentSagaService.STATUS_COMPENSATION_PENDING);
        when(keyValueRpcService.deleteNoteContent(CONTENT_UUID)).thenReturn(true);

        job.recover(saga);

        verify(keyValueRpcService).deleteNoteContent(CONTENT_UUID);
        verify(sagaService).markRolledBack(saga);
    }

    @Test
    void doesNotDeleteBodyWhenClearMetadataDidNotCommit() {
        NoteContentSagaDO saga = pendingSaga();
        saga.setOperationType(NoteContentSagaService.OPERATION_CLEAR);
        saga.setPayload(null);
        when(noteDOMapper.selectByPrimaryKeyIncludingDeleted(NOTE_ID)).thenReturn(NoteDO.builder()
                .id(NOTE_ID).contentUuid(CONTENT_UUID).isContentEmpty(false).status(1).build());

        job.recover(saga);

        verify(persistenceService).markNoteContentSagaCompleted(saga);
        verify(keyValueRpcService, never()).deleteNoteContent(anyString());
    }

    @Test
    void preservesBodyWhenClearRecoveryFindsSoftDeletedNoteStillPointingToIt() {
        NoteContentSagaDO saga = pendingSaga();
        saga.setOperationType(NoteContentSagaService.OPERATION_CLEAR);
        saga.setPayload(null);
        when(noteDOMapper.selectByPrimaryKeyIncludingDeleted(NOTE_ID)).thenReturn(NoteDO.builder()
                .id(NOTE_ID).contentUuid(CONTENT_UUID).isContentEmpty(false)
                .status(NoteStatusEnum.DELETED.getCode()).build());

        job.recover(saga);

        verify(persistenceService).markNoteContentSagaCompleted(saga);
        verify(persistenceService, never()).markOldNoteContentDeletePending(any(), anyString());
        verify(keyValueRpcService, never()).deleteNoteContent(anyString());
    }

    @Test
    void completesCompensationWithoutDeletingWhenTheUuidIsReferencedAgain() {
        NoteContentSagaDO saga = pendingSaga();
        saga.setOperationType(NoteContentSagaService.OPERATION_COMPENSATE);
        saga.setStatus(NoteContentSagaService.STATUS_COMPENSATION_PENDING);
        when(noteDOMapper.selectContentUuidByNoteId(NOTE_ID)).thenReturn(CONTENT_UUID);

        job.recover(saga);

        verify(persistenceService).markNoteContentSagaCompleted(saga);
        verify(keyValueRpcService, never()).deleteNoteContent(anyString());
        verify(sagaService, never()).markRolledBack(any());
    }

    @Test
    void failedRecoveryIsScheduledAgainWithoutCompletingTheSaga() {
        NoteContentSagaDO saga = pendingSaga();
        when(sagaService.claimBatch(anyString())).thenReturn(List.of(saga));
        when(noteDOMapper.selectByPrimaryKey(NOTE_ID)).thenReturn(null);
        when(keyValueRpcService.saveNoteContent(CONTENT_UUID, "random saga recovery body"))
                .thenThrow(new IllegalStateException("temporary Cassandra outage"));

        int recovered = job.recoverDueOperations();

        org.assertj.core.api.Assertions.assertThat(recovered).isEqualTo(1);
        verify(sagaService).scheduleRetry(eq(saga), any(IllegalStateException.class));
        verify(persistenceService, never()).insertPublishedNote(any(NoteDO.class), any(NoteContentSagaDO.class));
    }

    private static NoteContentSagaDO pendingSaga() {
        PublishNoteDTO dto = PublishNoteDTO.builder()
                .id(NOTE_ID)
                .creatorId(992345678901122L)
                .title("recovery test")
                .isContentEmpty(false)
                .contentUuid(CONTENT_UUID)
                .content("random saga recovery body")
                .status(1)
                .build();
        return NoteContentSagaDO.builder()
                .id(7L)
                .operationId(NoteContentSagaService.publishOperationId(NOTE_ID))
                .noteId(NOTE_ID)
                .operationType(NoteContentSagaService.OPERATION_PUBLISH)
                .contentUuid(CONTENT_UUID)
                .payload(JsonUtils.toJsonString(dto))
                .status(NoteContentSagaService.STATUS_PENDING)
                .leaseOwner("recovery-owner")
                .leaseVersion(2L)
                .attemptCount(1)
                .build();
    }
}
