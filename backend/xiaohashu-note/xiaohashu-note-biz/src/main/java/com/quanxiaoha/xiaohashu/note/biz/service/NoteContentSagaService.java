package com.quanxiaoha.xiaohashu.note.biz.service;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteContentSagaDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteContentSagaMapper;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.PublishNoteDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable retry state for a text-note body while its MySQL and Cassandra writes converge.
 */
@Service
public class NoteContentSagaService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DELETE_PENDING = "DELETE_PENDING";
    public static final String STATUS_COMPENSATION_PENDING = "COMPENSATION_PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_ROLLED_BACK = "ROLLED_BACK";
    public static final String OPERATION_PUBLISH = "PUBLISH";
    public static final String OPERATION_UPDATE = "UPDATE";
    public static final String OPERATION_CLEAR = "CLEAR";
    public static final String OPERATION_COMPENSATE = "COMPENSATE";

    private static final int BATCH_SIZE = 32;
    private static final int LEASE_SECONDS = 120;
    private static final int MAX_ERROR_LENGTH = 1000;

    @Resource
    private NoteContentSagaMapper sagaMapper;

    @Resource
    private NoteDOMapper noteDOMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public NoteContentSagaDO preparePublish(PublishNoteDTO dto, String owner) {
        if (dto == null || dto.getId() == null || dto.getContentUuid() == null || dto.getContent() == null) {
            throw new IllegalArgumentException("publish saga requires note ID, content UUID, and body");
        }
        return prepareOperation(publishOperationId(dto.getId()), dto.getId(), OPERATION_PUBLISH,
                dto.getContentUuid(), JsonUtils.toJsonString(dto), owner);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public NoteContentSagaDO prepareUpdate(Long noteId, String targetContentUuid, String owner) {
        if (noteId == null || targetContentUuid == null || targetContentUuid.isBlank()) {
            throw new IllegalArgumentException("note update saga requires note ID and target content UUID");
        }
        return prepareOperation("note-content-update:" + UUID.randomUUID(), noteId, OPERATION_UPDATE,
                targetContentUuid, null, owner);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public NoteContentSagaDO prepareClear(Long noteId, String oldContentUuid, String owner) {
        if (noteId == null || oldContentUuid == null || oldContentUuid.isBlank()) {
            throw new IllegalArgumentException("note clear saga requires the existing content UUID");
        }
        return prepareOperation("note-content-clear:" + UUID.randomUUID(), noteId, OPERATION_CLEAR,
                oldContentUuid, null, owner);
    }

    private NoteContentSagaDO prepareOperation(String operationId, Long noteId, String operationType,
                                               String contentUuid, String payload, String owner) {
        sagaMapper.insertIfAbsent(NoteContentSagaDO.builder()
                .operationId(operationId)
                .noteId(noteId)
                .operationType(operationType)
                .contentUuid(contentUuid)
                .payload(payload)
                .status(STATUS_PENDING)
                .build());

        NoteContentSagaDO saga = sagaMapper.selectByOperationIdForUpdate(operationId);
        if (saga == null) {
            throw new IllegalStateException("note content saga row was not created");
        }
        if (!contentUuid.equals(saga.getContentUuid()) && OPERATION_PUBLISH.equals(operationType)) {
            throw new IllegalStateException("note ID was reused with a different content UUID");
        }
        if (!contentUuid.equals(saga.getContentUuid()) || !noteId.equals(saga.getNoteId())
                || !operationType.equals(saga.getOperationType())) {
            throw new IllegalStateException("note content saga operation identity conflict");
        }
        if (!STATUS_PENDING.equals(saga.getStatus())) {
            return saga;
        }

        LocalDateTime now = LocalDateTime.now();
        if (saga.getLeaseUntil() == null || !saga.getLeaseUntil().isAfter(now)) {
            LocalDateTime leaseUntil = now.plusSeconds(LEASE_SECONDS);
            if (sagaMapper.acquireLease(saga.getId(), owner, leaseUntil, now) == 1) {
                saga.setLeaseOwner(owner);
                saga.setLeaseUntil(leaseUntil);
                saga.setLeaseVersion(saga.getLeaseVersion() == null ? 1L : saga.getLeaseVersion() + 1);
                saga.setAttemptCount(saga.getAttemptCount() == null ? 1 : saga.getAttemptCount() + 1);
            }
        }
        return saga;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<NoteContentSagaDO> claimBatch(String owner) {
        LocalDateTime now = LocalDateTime.now();
        List<NoteContentSagaDO> candidates = sagaMapper.selectClaimable(now, BATCH_SIZE);
        for (NoteContentSagaDO saga : candidates) {
            if (sagaMapper.acquireLease(saga.getId(), owner,
                    now.plusSeconds(LEASE_SECONDS), now) == 1) {
                saga.setLeaseOwner(owner);
                saga.setLeaseUntil(now.plusSeconds(LEASE_SECONDS));
                saga.setLeaseVersion(saga.getLeaseVersion() == null ? 1L : saga.getLeaseVersion() + 1);
                saga.setAttemptCount(saga.getAttemptCount() == null ? 1 : saga.getAttemptCount() + 1);
            }
        }
        return candidates.stream().filter(saga -> owner.equals(saga.getLeaseOwner())).toList();
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void markCompleted(NoteContentSagaDO saga) {
        requireLeaseUpdate(sagaMapper.markCompleted(saga.getId(), saga.getLeaseOwner(),
                saga.getLeaseVersion(), LocalDateTime.now()), "complete");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markDeletePending(NoteContentSagaDO saga, Throwable failure) {
        markCompensationPending(saga, saga.getContentUuid(), failure);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markCompensationPending(NoteContentSagaDO saga, String cleanupUuid, Throwable failure) {
        String error = errorSummary(failure);
        requireLeaseUpdate(sagaMapper.markCompensationPending(saga.getId(), saga.getLeaseOwner(),
                saga.getLeaseVersion(), cleanupUuid, error, LocalDateTime.now()), "schedule compensation");
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void markOldContentDeletePending(NoteContentSagaDO saga, String oldContentUuid) {
        if (oldContentUuid == null || oldContentUuid.isBlank()) {
            markCompleted(saga);
            return;
        }
        requireLeaseUpdate(sagaMapper.markDeletePending(saga.getId(), saga.getLeaseOwner(),
                saga.getLeaseVersion(), oldContentUuid, LocalDateTime.now()), "schedule old content cleanup");
    }

    /**
     * Locks a still-valid operation lease until the surrounding MySQL transaction commits its pointer update.
     * This fences recovery workers from compensating while an online request is committing.
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean lockLeaseForCommit(NoteContentSagaDO saga) {
        if (saga == null || saga.getId() == null || saga.getLeaseOwner() == null
                || saga.getLeaseVersion() == null) {
            return false;
        }
        return sagaMapper.selectActiveLeaseForUpdate(saga.getId(), saga.getLeaseOwner(),
                saga.getLeaseVersion()) != null;
    }

    /**
     * Reopens an independent cleanup record after a Cassandra write finishes after its original lease expired.
     * The UUID-keyed row can be recreated even when the original saga was already marked rolled back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void requeueLateWriteCleanup(NoteContentSagaDO originalSaga, String contentUuid, Throwable failure) {
        if (originalSaga == null || originalSaga.getNoteId() == null
                || contentUuid == null || contentUuid.isBlank()) {
            throw new IllegalArgumentException("late content cleanup requires note ID and content UUID");
        }
        if (Objects.equals(contentUuid, noteDOMapper.selectContentUuidByNoteId(originalSaga.getNoteId()))) {
            return;
        }
        sagaMapper.upsertCompensationPending(NoteContentSagaDO.builder()
                .operationId(compensationOperationId(contentUuid))
                .noteId(originalSaga.getNoteId())
                .operationType(OPERATION_COMPENSATE)
                .contentUuid(contentUuid)
                .status(STATUS_COMPENSATION_PENDING)
                .lastError(errorSummary(failure))
                .build());
    }

    @Transactional(rollbackFor = Exception.class)
    public void markRolledBack(NoteContentSagaDO saga) {
        requireLeaseUpdate(sagaMapper.markRolledBack(saga.getId(), saga.getLeaseOwner(),
                saga.getLeaseVersion(), LocalDateTime.now()), "mark rollback");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void scheduleRetry(NoteContentSagaDO saga, Throwable failure) {
        int attempt = saga.getAttemptCount() == null ? 1 : saga.getAttemptCount();
        long delaySeconds = Math.min(300L, 1L << Math.min(attempt, 8));
        LocalDateTime now = LocalDateTime.now();
        requireLeaseUpdate(sagaMapper.scheduleRetry(saga.getId(), saga.getLeaseOwner(),
                saga.getLeaseVersion(), now.plusSeconds(delaySeconds), errorSummary(failure), now),
                "schedule retry");
    }

    public NoteContentSagaDO find(String operationId) {
        return sagaMapper.selectByOperationId(operationId);
    }

    public static String publishOperationId(Long noteId) {
        if (noteId == null) {
            throw new IllegalArgumentException("note ID is required");
        }
        return "note-content-publish:" + noteId;
    }

    public static String compensationOperationId(String contentUuid) {
        if (contentUuid == null || contentUuid.isBlank()) {
            throw new IllegalArgumentException("content UUID is required");
        }
        String operationId = "note-content-compensate:" + contentUuid;
        if (operationId.length() > 64) {
            throw new IllegalArgumentException("content UUID is too long for a compensation operation ID");
        }
        return operationId;
    }

    private static void requireLeaseUpdate(int updatedRows, String action) {
        if (updatedRows != 1) {
            throw new IllegalStateException("note content saga lease was lost before " + action);
        }
    }

    private static String errorSummary(Throwable failure) {
        String error = failure == null ? "unknown error" : failure.toString();
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
