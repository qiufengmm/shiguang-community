package com.quanxiaoha.xiaohashu.note.biz.service;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.note.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteOperateOutboxDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteOperateOutboxMapper;
import com.quanxiaoha.xiaohashu.note.biz.enums.NoteOperateEnum;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.NoteOperateMqDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class NoteOperateOutboxService {

    private static final int BATCH_SIZE = 100;
    private static final int LEASE_SECONDS = 30;
    private static final int MAX_ERROR_LENGTH = 1000;

    @Resource
    private NoteOperateOutboxMapper outboxMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Long creatorId, Long noteId, Integer operationType) {
        String eventId = eventId(noteId, operationType);
        NoteOperateMqDTO message = NoteOperateMqDTO.builder()
                .eventId(eventId)
                .creatorId(creatorId)
                .noteId(noteId)
                .type(operationType)
                .build();
        String tag = operationType.equals(NoteOperateEnum.PUBLISH.getCode())
                ? MQConstants.TAG_NOTE_PUBLISH : MQConstants.TAG_NOTE_DELETE;
        NoteOperateOutboxDO record = NoteOperateOutboxDO.builder()
                .eventId(eventId)
                .creatorId(creatorId)
                .noteId(noteId)
                .operationType(operationType)
                .topic(MQConstants.TOPIC_NOTE_OPERATE + ":" + tag)
                .payload(JsonUtils.toJsonString(message))
                .nextAttemptAt(LocalDateTime.now())
                .build();
        outboxMapper.insertIfAbsent(record);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueCacheInvalidation(Long creatorId, Long noteId, String topic, String payload,
                                         LocalDateTime availableAt) {
        String eventId = "note-cache:" + noteId + ":" + UUID.randomUUID();
        NoteOperateOutboxDO record = NoteOperateOutboxDO.builder()
                .eventId(eventId)
                .creatorId(creatorId)
                .noteId(noteId)
                .operationType(NoteOperateEnum.CACHE_INVALIDATION.getCode())
                .topic(topic)
                .payload(payload)
                .nextAttemptAt(availableAt)
                .build();
        outboxMapper.insertIfAbsent(record);
    }

    @Transactional(rollbackFor = Exception.class)
    public List<NoteOperateOutboxDO> claimBatch(String owner) {
        LocalDateTime now = LocalDateTime.now();
        List<NoteOperateOutboxDO> records = outboxMapper.selectClaimable(now, BATCH_SIZE);
        for (NoteOperateOutboxDO record : records) {
            if (outboxMapper.acquireLease(record.getId(), owner, now.plusSeconds(LEASE_SECONDS), now) == 1) {
                record.setLeaseOwner(owner);
                record.setLeaseUntil(now.plusSeconds(LEASE_SECONDS));
                record.setLeaseVersion(record.getLeaseVersion() + 1);
                record.setAttemptCount(record.getAttemptCount() + 1);
            }
        }
        return records.stream().filter(record -> owner.equals(record.getLeaseOwner())).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void markSent(NoteOperateOutboxDO record) {
        LocalDateTime now = LocalDateTime.now();
        if (outboxMapper.markSent(record.getId(), record.getLeaseOwner(), record.getLeaseVersion(), now) != 1) {
            throw new IllegalStateException("note outbox lease was lost before marking SENT");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void scheduleRetry(NoteOperateOutboxDO record, Throwable failure) {
        int attempt = record.getAttemptCount() == null ? 1 : record.getAttemptCount();
        long delaySeconds = Math.min(300L, 1L << Math.min(attempt, 8));
        String error = failure == null ? "unknown error" : failure.toString();
        if (error.length() > MAX_ERROR_LENGTH) error = error.substring(0, MAX_ERROR_LENGTH);
        LocalDateTime now = LocalDateTime.now();
        if (outboxMapper.scheduleRetry(record.getId(), record.getLeaseOwner(), record.getLeaseVersion(),
                now.plusSeconds(delaySeconds), error, now) != 1) {
            throw new IllegalStateException("note outbox lease was lost before scheduling retry");
        }
    }

    public static String eventId(Long noteId, Integer operationType) {
        if (noteId == null || operationType == null) {
            throw new IllegalArgumentException("noteId and operationType are required for a stable event ID");
        }
        return "note-operate:" + noteId + ":" + operationType;
    }
}
