package com.quanxiaoha.xiaohashu.note.biz.service;

import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteContentSagaDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NotePublishCountLockMapper;
import com.quanxiaoha.xiaohashu.note.biz.enums.NoteOperateEnum;
import com.quanxiaoha.xiaohashu.note.biz.enums.NoteStatusEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class NoteOperatePersistenceService {

    @Resource
    private NotePublishCountLockMapper lockMapper;

    @Resource
    private NoteDOMapper noteDOMapper;

    @Resource
    private NoteOperateOutboxService outboxService;

    @Resource
    private NoteContentSagaService noteContentSagaService;

    @Transactional(rollbackFor = Exception.class)
    public void insertPublishedNote(NoteDO note) {
        lockCreator(note.getCreatorId());
        noteDOMapper.insert(note);
        outboxService.enqueue(note.getCreatorId(), note.getId(), NoteOperateEnum.PUBLISH.getCode());
    }

    @Transactional(rollbackFor = Exception.class)
    public void insertPublishedNote(NoteDO note, NoteContentSagaDO contentSaga) {
        lockCreator(note.getCreatorId());
        noteDOMapper.insert(note);
        outboxService.enqueue(note.getCreatorId(), note.getId(), NoteOperateEnum.PUBLISH.getCode());
        noteContentSagaService.markCompleted(contentSaga);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markNoteContentSagaCompleted(NoteContentSagaDO contentSaga) {
        noteContentSagaService.markCompleted(contentSaga);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markOldNoteContentDeletePending(NoteContentSagaDO contentSaga, String oldContentUuid) {
        noteContentSagaService.markOldContentDeletePending(contentSaga, oldContentUuid);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean softDeleteNote(Long noteId, Long requesterId) {
        NoteDO current = noteDOMapper.selectByPrimaryKey(noteId);
        if (current == null) return false;
        if (!requesterId.equals(current.getCreatorId())) {
            throw new IllegalStateException("only the note creator can delete the note");
        }

        lockCreator(current.getCreatorId());
        current = noteDOMapper.selectByPrimaryKey(noteId);
        if (current == null || Objects.equals(current.getStatus(), NoteStatusEnum.DELETED.getCode())) return false;

        NoteDO update = NoteDO.builder()
                .id(noteId)
                .status(NoteStatusEnum.DELETED.getCode())
                .updateTime(java.time.LocalDateTime.now())
                .build();
        noteDOMapper.updateByPrimaryKeySelective(update);
        outboxService.enqueue(current.getCreatorId(), noteId, NoteOperateEnum.DELETE.getCode());
        return true;
    }

    private void lockCreator(Long creatorId) {
        lockMapper.ensureLockRow(creatorId);
        if (lockMapper.lockByUserId(creatorId) == null) {
            throw new IllegalStateException("could not acquire note publish count lock");
        }
    }
}
