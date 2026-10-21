package com.quanxiaoha.xiaohashu.note.biz.service;

import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteContentSagaDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteContentSagaMapper;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.PublishNoteDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NoteContentSagaServiceTest {

    private static final Long NOTE_ID = 991234567890111L;
    private static final String CONTENT_UUID = "11111111-1111-4111-8111-111111111111";

    private final NoteContentSagaMapper mapper = mock(NoteContentSagaMapper.class);
    private final NoteDOMapper noteDOMapper = mock(NoteDOMapper.class);
    private final NoteContentSagaService service = new NoteContentSagaService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "sagaMapper", mapper);
        ReflectionTestUtils.setField(service, "noteDOMapper", noteDOMapper);
    }

    @Test
    void preparePublishPersistsPayloadAndClaimsTheSagaBeforeReturning() {
        when(mapper.selectByOperationIdForUpdate(NoteContentSagaService.publishOperationId(NOTE_ID)))
                .thenReturn(pendingSaga());
        when(mapper.acquireLease(eq(7L), eq("publisher-owner"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);

        NoteContentSagaDO claimed = service.preparePublish(publishNote(), "publisher-owner");

        verify(mapper).insertIfAbsent(any(NoteContentSagaDO.class));
        verify(mapper).acquireLease(eq(7L), eq("publisher-owner"), any(LocalDateTime.class), any(LocalDateTime.class));
        assertThat(claimed.getLeaseOwner()).isEqualTo("publisher-owner");
        assertThat(claimed.getLeaseVersion()).isEqualTo(1L);
        assertThat(claimed.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void preparePublishDoesNotReuseAnOperationForAnotherContentUuid() {
        NoteContentSagaDO existing = pendingSaga();
        existing.setContentUuid("22222222-2222-4222-8222-222222222222");
        when(mapper.selectByOperationIdForUpdate(NoteContentSagaService.publishOperationId(NOTE_ID)))
                .thenReturn(existing);

        assertThatThrownBy(() -> service.preparePublish(publishNote(), "publisher-owner"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different content UUID");
    }

    @Test
    void claimBatchReturnsOnlyRowsWithTheCurrentLeaseOwner() {
        NoteContentSagaDO candidate = pendingSaga();
        when(mapper.selectClaimable(any(LocalDateTime.class), eq(32))).thenReturn(List.of(candidate));
        when(mapper.acquireLease(eq(7L), eq("recovery-owner"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);

        List<NoteContentSagaDO> claimed = service.claimBatch("recovery-owner");

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).getLeaseOwner()).isEqualTo("recovery-owner");
        assertThat(claimed.get(0).getLeaseVersion()).isEqualTo(1L);
    }

    @Test
    void retryUsesLeaseVersionAndSchedulesCappedBackoff() {
        NoteContentSagaDO saga = pendingSaga();
        saga.setLeaseOwner("recovery-owner");
        saga.setLeaseVersion(3L);
        saga.setAttemptCount(9);
        when(mapper.scheduleRetry(eq(7L), eq("recovery-owner"), eq(3L),
                any(LocalDateTime.class), any(String.class), any(LocalDateTime.class))).thenReturn(1);

        service.scheduleRetry(saga, new IllegalStateException("temporary failure"));

        verify(mapper).scheduleRetry(eq(7L), eq("recovery-owner"), eq(3L),
                any(LocalDateTime.class), eq("java.lang.IllegalStateException: temporary failure"),
                any(LocalDateTime.class));
    }

    @Test
    void leaseMustStillBeActiveAndLockedBeforeContentPointerCanCommit() {
        NoteContentSagaDO saga = pendingSaga();
        saga.setLeaseOwner("publisher-owner");
        saga.setLeaseVersion(4L);
        when(mapper.selectActiveLeaseForUpdate(7L, "publisher-owner", 4L)).thenReturn(7L);

        assertThat(service.lockLeaseForCommit(saga)).isTrue();

        verify(mapper).selectActiveLeaseForUpdate(7L, "publisher-owner", 4L);
    }

    @Test
    void lateWriteCreatesIndependentDurableCompensationRecord() {
        NoteContentSagaDO original = pendingSaga();
        String lateUuid = "22222222-2222-4222-8222-222222222222";

        service.requeueLateWriteCleanup(original, lateUuid, new IllegalStateException("expired lease"));

        org.mockito.ArgumentCaptor<NoteContentSagaDO> sagaCaptor =
                org.mockito.ArgumentCaptor.forClass(NoteContentSagaDO.class);
        verify(mapper).upsertCompensationPending(sagaCaptor.capture());
        NoteContentSagaDO queued = sagaCaptor.getValue();
        assertThat(queued.getOperationId()).isEqualTo(NoteContentSagaService.compensationOperationId(lateUuid));
        assertThat(queued.getNoteId()).isEqualTo(NOTE_ID);
        assertThat(queued.getOperationType()).isEqualTo(NoteContentSagaService.OPERATION_COMPENSATE);
        assertThat(queued.getContentUuid()).isEqualTo(lateUuid);
        assertThat(queued.getStatus()).isEqualTo(NoteContentSagaService.STATUS_COMPENSATION_PENDING);
        assertThat(queued.getLastError()).contains("expired lease");
    }

    @Test
    void doesNotQueueCompensationForBodyStillReferencedByMySql() {
        NoteContentSagaDO original = pendingSaga();
        when(noteDOMapper.selectContentUuidByNoteId(NOTE_ID)).thenReturn(CONTENT_UUID);

        service.requeueLateWriteCleanup(original, CONTENT_UUID, new IllegalStateException("stale worker"));

        verify(mapper, org.mockito.Mockito.never()).upsertCompensationPending(any(NoteContentSagaDO.class));
    }

    private static PublishNoteDTO publishNote() {
        return PublishNoteDTO.builder()
                .id(NOTE_ID)
                .creatorId(992345678901122L)
                .isContentEmpty(false)
                .contentUuid(CONTENT_UUID)
                .content("random saga test body")
                .build();
    }

    private static NoteContentSagaDO pendingSaga() {
        return NoteContentSagaDO.builder()
                .id(7L)
                .operationId(NoteContentSagaService.publishOperationId(NOTE_ID))
                .noteId(NOTE_ID)
                .operationType(NoteContentSagaService.OPERATION_PUBLISH)
                .contentUuid(CONTENT_UUID)
                .payload("{}")
                .status(NoteContentSagaService.STATUS_PENDING)
                .attemptCount(0)
                .leaseVersion(0L)
                .build();
    }
}
