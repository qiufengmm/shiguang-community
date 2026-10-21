package com.quanxiaoha.xiaohashu.note.biz.service.impl;

import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteCollectionDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteLikeDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindNoteIsLikedAndCollectedReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindNoteIsLikedAndCollectedRspVO;
import com.quanxiaoha.xiaohashu.note.biz.service.NoteIdSetCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NoteLikeCollectStatusTest {

    private static final Long USER_ID = 971234567890123L;
    private static final Long NOTE_ID = 981234567890123L;

    private NoteServiceImpl noteService;
    private NoteLikeDOMapper noteLikeDOMapper;
    private NoteCollectionDOMapper noteCollectionDOMapper;
    private RedisTemplate<String, String> redisTemplate;
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;
    private NoteIdSetCache noteIdSetCache;

    @BeforeEach
    void setUp() {
        noteService = new NoteServiceImpl();
        noteLikeDOMapper = mock(NoteLikeDOMapper.class);
        noteCollectionDOMapper = mock(NoteCollectionDOMapper.class);
        redisTemplate = mock(RedisTemplate.class);
        threadPoolTaskExecutor = mock(ThreadPoolTaskExecutor.class);
        noteIdSetCache = mock(NoteIdSetCache.class);

        ReflectionTestUtils.setField(noteService, "noteDOMapper", mock(NoteDOMapper.class));
        ReflectionTestUtils.setField(noteService, "noteLikeDOMapper", noteLikeDOMapper);
        ReflectionTestUtils.setField(noteService, "noteCollectionDOMapper", noteCollectionDOMapper);
        ReflectionTestUtils.setField(noteService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(noteService, "threadPoolTaskExecutor", threadPoolTaskExecutor);
        ReflectionTestUtils.setField(noteService, "noteIdSetCache", noteIdSetCache);
        LoginUserContextHolder.remove();
    }

    @AfterEach
    void clearContext() {
        LoginUserContextHolder.remove();
    }

    @Test
    void anonymousUserGetsFalseLikeAndCollectionFlags() {
        Response<?> response = invokeStatusEndpoint();

        assertTrue(response.isSuccess());
        FindNoteIsLikedAndCollectedRspVO data = (FindNoteIsLikedAndCollectedRspVO) response.getData();
        assertEquals(NOTE_ID, data.getNoteId());
        assertEquals(Boolean.FALSE, data.getIsLiked());
        assertEquals(Boolean.FALSE, data.getIsCollected());
        verify(redisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), any(Object.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void loggedInUserGetsFlagsFromReadyIdSets() {
        LoginUserContextHolder.setUserId(USER_ID);
        when(noteIdSetCache.contains(anyString(), anyString(), anyLong())).thenReturn(1L, 1L);

        Response<?> response = invokeStatusEndpoint();

        assertTrue(response.isSuccess());
        FindNoteIsLikedAndCollectedRspVO data = (FindNoteIsLikedAndCollectedRspVO) response.getData();
        assertEquals(Boolean.TRUE, data.getIsLiked());
        assertEquals(Boolean.TRUE, data.getIsCollected());
        verify(noteLikeDOMapper, never()).selectCountByUserIdAndNoteId(USER_ID, NOTE_ID);
        verify(noteCollectionDOMapper, never()).selectCountByUserIdAndNoteId(USER_ID, NOTE_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingBitmapsFallBackToDatabaseAndScheduleBackfillForExistingRecords() {
        LoginUserContextHolder.setUserId(USER_ID);
        when(noteIdSetCache.contains(anyString(), anyString(), anyLong())).thenReturn(-1L, -1L);
        when(noteLikeDOMapper.selectCountByUserIdAndNoteId(USER_ID, NOTE_ID)).thenReturn(1);
        when(noteCollectionDOMapper.selectCountByUserIdAndNoteId(USER_ID, NOTE_ID)).thenReturn(0);

        Response<?> response = invokeStatusEndpoint();

        FindNoteIsLikedAndCollectedRspVO data = (FindNoteIsLikedAndCollectedRspVO) response.getData();
        assertEquals(Boolean.TRUE, data.getIsLiked());
        assertEquals(Boolean.FALSE, data.getIsCollected());
        verify(threadPoolTaskExecutor, org.mockito.Mockito.times(2)).submit(any(Runnable.class));
    }

    private Response<FindNoteIsLikedAndCollectedRspVO> invokeStatusEndpoint() {
        return noteService.isLikedAndCollectedData(
                FindNoteIsLikedAndCollectedReqVO.builder().noteId(NOTE_ID).build());
    }
}
