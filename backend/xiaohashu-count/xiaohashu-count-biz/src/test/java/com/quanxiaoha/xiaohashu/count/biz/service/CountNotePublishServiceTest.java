package com.quanxiaoha.xiaohashu.count.biz.service;

import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.NotePublishCountEventMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.NotePublishCountLockMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.UserCountDOMapper;
import com.quanxiaoha.xiaohashu.count.biz.model.dto.NoteOperateMqDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CountNotePublishServiceTest {

    private NotePublishCountEventMapper eventMapper;
    private NotePublishCountLockMapper lockMapper;
    private UserCountDOMapper userCountDOMapper;
    private CountNotePublishService service;

    @BeforeEach
    void setUp() {
        eventMapper = mock(NotePublishCountEventMapper.class);
        lockMapper = mock(NotePublishCountLockMapper.class);
        userCountDOMapper = mock(UserCountDOMapper.class);
        when(lockMapper.ensureLockRow(301L)).thenReturn(1);
        when(lockMapper.lockByUserId(301L)).thenReturn(301L);

        service = new CountNotePublishService();
        ReflectionTestUtils.setField(service, "eventMapper", eventMapper);
        ReflectionTestUtils.setField(service, "lockMapper", lockMapper);
        ReflectionTestUtils.setField(service, "userCountDOMapper", userCountDOMapper);
    }

    @Test
    void duplicatePublishEventIsAppliedOnlyOnce() {
        NoteOperateMqDTO event = NoteOperateMqDTO.builder()
                .eventId("note-operate:201:1")
                .creatorId(301L)
                .noteId(201L)
                .type(1)
                .build();
        when(eventMapper.insertIfAbsent("note-operate:201:1", 301L, 201L, 1))
                .thenReturn(1)
                .thenReturn(0);
        when(eventMapper.selectUserIdByEventId("note-operate:201:1")).thenReturn(301L);

        assertThat(service.apply(event, 1L)).isTrue();
        assertThat(service.apply(event, 1L)).isFalse();

        verify(userCountDOMapper, times(1)).insertOrUpdateNoteTotalByUserId(1L, 301L);
        verify(lockMapper, times(2)).lockByUserId(301L);
    }

    @Test
    void legacyMessageUsesStableFallbackEventKey() {
        NoteOperateMqDTO event = NoteOperateMqDTO.builder()
                .creatorId(301L)
                .noteId(201L)
                .type(1)
                .build();
        when(eventMapper.insertIfAbsent("legacy-note-operate:201:1", 301L, 201L, 1)).thenReturn(1);

        assertThat(service.apply(event, 1L)).isTrue();

        verify(userCountDOMapper).insertOrUpdateNoteTotalByUserId(1L, 301L);
    }

    @Test
    void refusesToApplyEventWhenThePerCreatorLockWasNotAcquired() {
        NoteOperateMqDTO event = NoteOperateMqDTO.builder()
                .eventId("note-operate:201:1")
                .creatorId(301L)
                .noteId(201L)
                .type(1)
                .build();
        when(lockMapper.lockByUserId(301L)).thenReturn(null);

        assertThatThrownBy(() -> service.apply(event, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not acquire");

        verify(eventMapper, never()).insertIfAbsent("note-operate:201:1", 301L, 201L, 1);
        verify(userCountDOMapper, never()).insertOrUpdateNoteTotalByUserId(1L, 301L);
    }

    @Test
    void rejectsAnEventWhoseDeltaConflictsWithItsOperationType() {
        NoteOperateMqDTO event = NoteOperateMqDTO.builder()
                .eventId("note-operate:201:1")
                .creatorId(301L)
                .noteId(201L)
                .type(1)
                .build();

        assertThatThrownBy(() -> service.apply(event, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");

        verify(lockMapper, never()).ensureLockRow(301L);
        verify(eventMapper, never()).insertIfAbsent("note-operate:201:1", 301L, 201L, 1);
        verify(userCountDOMapper, never()).insertOrUpdateNoteTotalByUserId(-1L, 301L);
    }
}
