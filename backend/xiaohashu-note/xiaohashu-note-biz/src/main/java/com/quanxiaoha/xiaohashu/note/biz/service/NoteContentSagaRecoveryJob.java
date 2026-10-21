package com.quanxiaoha.xiaohashu.note.biz.service;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteContentSagaDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.enums.NoteStatusEnum;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.PublishNoteDTO;
import com.quanxiaoha.xiaohashu.note.biz.rpc.KeyValueRpcService;
import com.quanxiaoha.xiaohashu.note.biz.convert.NoteConvert;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Retries unresolved note-body writes and orphan cleanup after service/process recovery. */
@Component
@Slf4j
public class NoteContentSagaRecoveryJob {

    @Resource
    private NoteContentSagaService sagaService;

    @Resource
    private NoteDOMapper noteDOMapper;

    @Resource
    private NoteOperatePersistenceService persistenceService;

    @Resource
    private KeyValueRpcService keyValueRpcService;

    @Scheduled(fixedDelayString = "${note.content-saga.recovery-delay-ms:2000}")
    public int recoverDueOperations() {
        List<NoteContentSagaDO> batch = sagaService.claimBatch(UUID.randomUUID().toString());
        for (NoteContentSagaDO saga : batch) {
            try {
                recover(saga);
            } catch (Exception e) {
                try {
                    sagaService.scheduleRetry(saga, e);
                } catch (Exception retryError) {
                    log.error("Note content saga retry state update failed, noteId={}, operationId={}",
                            saga.getNoteId(), saga.getOperationId(), retryError);
                }
                log.error("Note content saga recovery failed, noteId={}, operationId={}, status={}",
                        saga.getNoteId(), saga.getOperationId(), saga.getStatus(), e);
            }
        }
        return batch.size();
    }

    void recover(NoteContentSagaDO saga) {
        if (NoteContentSagaService.STATUS_DELETE_PENDING.equals(saga.getStatus())) {
            cleanupContent(saga);
            return;
        }
        if (NoteContentSagaService.STATUS_COMPENSATION_PENDING.equals(saga.getStatus())) {
            compensateContent(saga);
            return;
        }
        if (!NoteContentSagaService.STATUS_PENDING.equals(saga.getStatus())) {
            return;
        }

        if (NoteContentSagaService.OPERATION_UPDATE.equals(saga.getOperationType())) {
            recoverUpdate(saga);
            return;
        }
        if (NoteContentSagaService.OPERATION_CLEAR.equals(saga.getOperationType())) {
            recoverClear(saga);
            return;
        }
        if (!NoteContentSagaService.OPERATION_PUBLISH.equals(saga.getOperationType())) {
            sagaService.markCompensationPending(saga, saga.getContentUuid(),
                    new IllegalStateException("unsupported note content operation"));
            return;
        }

        PublishNoteDTO dto = JsonUtils.parseObject(saga.getPayload(), PublishNoteDTO.class);
        if (dto == null || !Objects.equals(dto.getId(), saga.getNoteId())
                || !Objects.equals(dto.getContentUuid(), saga.getContentUuid())) {
            sagaService.markCompensationPending(saga, saga.getContentUuid(),
                    new IllegalStateException("invalid durable note publish payload"));
            return;
        }

        NoteDO persistedNote = noteDOMapper.selectByPrimaryKey(saga.getNoteId());
        if (persistedNote != null) {
            if (Objects.equals(saga.getContentUuid(), persistedNote.getContentUuid())
                    && !Objects.equals(NoteStatusEnum.DELETED.getCode(), persistedNote.getStatus())) {
                saveContentOrThrow(saga.getContentUuid(), dto.getContent());
                persistenceService.markNoteContentSagaCompleted(saga);
            } else {
                sagaService.markCompensationPending(saga, saga.getContentUuid(),
                        new IllegalStateException("note metadata references another content UUID"));
            }
            return;
        }

        saveContentOrThrow(saga.getContentUuid(), dto.getContent());
        try {
            persistenceService.insertPublishedNote(NoteConvert.INSTANCE.convertDTO2DO(dto), saga);
        } catch (Exception e) {
            String persistedUuid = noteDOMapper.selectContentUuidByNoteId(saga.getNoteId());
            if (Objects.equals(saga.getContentUuid(), persistedUuid)) {
                persistenceService.markNoteContentSagaCompleted(saga);
                return;
            }
            if (persistedUuid != null || isDefiniteSqlRejection(e)) {
                sagaService.markCompensationPending(saga, saga.getContentUuid(), e);
                return;
            }
            throw e;
        }
    }

    private void saveContentOrThrow(String contentUuid, String content) {
        if (!keyValueRpcService.saveNoteContent(contentUuid, content)) {
            throw new IllegalStateException("KV service did not confirm note content save");
        }
    }

    private void cleanupContent(NoteContentSagaDO saga) {
        if (saga.getContentUuid() != null && !keyValueRpcService.deleteNoteContent(saga.getContentUuid())) {
            throw new IllegalStateException("KV service did not confirm orphan content cleanup");
        }
        persistenceService.markNoteContentSagaCompleted(saga);
    }

    private void compensateContent(NoteContentSagaDO saga) {
        if (saga.getContentUuid() != null
                && Objects.equals(saga.getContentUuid(), noteDOMapper.selectContentUuidByNoteId(saga.getNoteId()))) {
            // A late or ambiguous pointer commit won the race: preserve the referenced body.
            persistenceService.markNoteContentSagaCompleted(saga);
            return;
        }
        if (saga.getContentUuid() != null && !keyValueRpcService.deleteNoteContent(saga.getContentUuid())) {
            throw new IllegalStateException("KV service did not confirm compensating content cleanup");
        }
        sagaService.markRolledBack(saga);
    }

    private void recoverUpdate(NoteContentSagaDO saga) {
        NoteDO persistedNote = noteDOMapper.selectByPrimaryKey(saga.getNoteId());
        if (persistedNote != null && Objects.equals(saga.getContentUuid(), persistedNote.getContentUuid())
                && !Objects.equals(NoteStatusEnum.DELETED.getCode(), persistedNote.getStatus())) {
            persistenceService.markNoteContentSagaCompleted(saga);
            return;
        }
        sagaService.markCompensationPending(saga, saga.getContentUuid(),
                new IllegalStateException("note update did not commit; removing its unreferenced body"));
    }

    private void recoverClear(NoteContentSagaDO saga) {
        NoteDO persistedNote = noteDOMapper.selectByPrimaryKeyIncludingDeleted(saga.getNoteId());
        if (persistedNote == null || Boolean.TRUE.equals(persistedNote.getIsContentEmpty())
                || !Objects.equals(saga.getContentUuid(), persistedNote.getContentUuid())) {
            persistenceService.markOldNoteContentDeletePending(saga, saga.getContentUuid());
            return;
        }
        persistenceService.markNoteContentSagaCompleted(saga);
    }

    private boolean isDefiniteSqlRejection(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof DataIntegrityViolationException
                    || current instanceof BadSqlGrammarException
                    || current instanceof InvalidDataAccessApiUsageException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
