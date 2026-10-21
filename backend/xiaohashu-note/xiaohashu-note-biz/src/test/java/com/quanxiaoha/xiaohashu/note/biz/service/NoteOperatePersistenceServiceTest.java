package com.quanxiaoha.xiaohashu.note.biz.service;

import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteContentSagaDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NotePublishCountLockMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NoteOperatePersistenceServiceTest {

    private NotePublishCountLockMapper lockMapper;
    private NoteDOMapper noteDOMapper;
    private NoteOperateOutboxService outboxService;
    private NoteContentSagaService contentSagaService;
    private NoteOperatePersistenceService persistenceService;

    @BeforeEach
    void setUp() {
        lockMapper = mock(NotePublishCountLockMapper.class);
        noteDOMapper = mock(NoteDOMapper.class);
        outboxService = mock(NoteOperateOutboxService.class);
        contentSagaService = mock(NoteContentSagaService.class);
        when(lockMapper.ensureLockRow(301L)).thenReturn(1);
        when(lockMapper.lockByUserId(301L)).thenReturn(301L);

        persistenceService = new NoteOperatePersistenceService();
        ReflectionTestUtils.setField(persistenceService, "lockMapper", lockMapper);
        ReflectionTestUtils.setField(persistenceService, "noteDOMapper", noteDOMapper);
        ReflectionTestUtils.setField(persistenceService, "outboxService", outboxService);
        ReflectionTestUtils.setField(persistenceService, "noteContentSagaService", contentSagaService);
    }

    @Test
    void publishedNoteAndCountEventArePersistedUnderTheSameCreatorLock() {
        NoteDO note = NoteDO.builder().id(201L).creatorId(301L).status(1).build();

        persistenceService.insertPublishedNote(note);

        var order = inOrder(lockMapper, noteDOMapper, outboxService);
        order.verify(lockMapper).ensureLockRow(301L);
        order.verify(lockMapper).lockByUserId(301L);
        order.verify(noteDOMapper).insert(note);
        order.verify(outboxService).enqueue(301L, 201L, 1);
    }

    @Test
    void publishedNoteCountEventAndContentSagaCompleteTogether() {
        NoteDO note = NoteDO.builder().id(202L).creatorId(301L).status(1).build();
        NoteContentSagaDO saga = NoteContentSagaDO.builder().id(9L).noteId(202L).build();

        persistenceService.insertPublishedNote(note, saga);

        var order = inOrder(lockMapper, noteDOMapper, outboxService, contentSagaService);
        order.verify(lockMapper).ensureLockRow(301L);
        order.verify(lockMapper).lockByUserId(301L);
        order.verify(noteDOMapper).insert(note);
        order.verify(outboxService).enqueue(301L, 202L, 1);
        order.verify(contentSagaService).markCompleted(saga);
    }
}
