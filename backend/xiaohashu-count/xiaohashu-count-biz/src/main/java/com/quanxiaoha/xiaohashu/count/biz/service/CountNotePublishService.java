package com.quanxiaoha.xiaohashu.count.biz.service;

import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.NotePublishCountEventMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.NotePublishCountLockMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.UserCountDOMapper;
import com.quanxiaoha.xiaohashu.count.biz.model.dto.NoteOperateMqDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class CountNotePublishService {

    @Resource
    private NotePublishCountEventMapper eventMapper;

    @Resource
    private NotePublishCountLockMapper lockMapper;

    @Resource
    private UserCountDOMapper userCountDOMapper;

    /**
     * Applies a note publish/delete delta once. The event ledger and counter change
     * share one MySQL transaction, so RocketMQ redelivery cannot double count.
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean apply(NoteOperateMqDTO event, long delta) {
        if (event == null || event.getCreatorId() == null || event.getNoteId() == null) {
            throw new IllegalArgumentException("note count event is missing creatorId or noteId");
        }

        int operationType = event.getType() == null ? (delta > 0 ? 1 : 0) : event.getType();
        long expectedDelta = switch (operationType) {
            case 0 -> -1L;
            case 1 -> 1L;
            default -> throw new IllegalArgumentException("unsupported note count operation type: " + operationType);
        };
        if (delta != expectedDelta) {
            throw new IllegalArgumentException("note count delta does not match its operation type");
        }
        String eventId = StringUtils.hasText(event.getEventId())
                ? event.getEventId()
                : "legacy-note-operate:" + event.getNoteId() + ":" + operationType;
        if (StringUtils.hasText(event.getEventId())
                && !eventId.equals("note-operate:" + event.getNoteId() + ":" + operationType)) {
            throw new IllegalArgumentException("note count event ID does not match the note operation");
        }

        lockMapper.ensureLockRow(event.getCreatorId());
        if (lockMapper.lockByUserId(event.getCreatorId()) == null) {
            throw new IllegalStateException("could not acquire note publish count lock");
        }

        int inserted = eventMapper.insertIfAbsent(eventId, event.getCreatorId(), event.getNoteId(), operationType);
        if (inserted == 0) {
            Long existingUserId = eventMapper.selectUserIdByEventId(eventId);
            if (!event.getCreatorId().equals(existingUserId)) {
                throw new IllegalStateException("note count event ID is already assigned to a different user");
            }
            return false;
        }

        userCountDOMapper.insertOrUpdateNoteTotalByUserId(delta, event.getCreatorId());
        return true;
    }
}
