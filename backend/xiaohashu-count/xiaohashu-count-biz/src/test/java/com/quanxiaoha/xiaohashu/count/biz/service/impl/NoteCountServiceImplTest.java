package com.quanxiaoha.xiaohashu.count.biz.service.impl;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.biz.domain.dataobject.NoteCountDO;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.NoteCountDOMapper;
import com.quanxiaoha.xiaohashu.count.dto.FindNoteCountsByIdRspDTO;
import com.quanxiaoha.xiaohashu.count.dto.FindNoteCountsByIdsReqDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NoteCountServiceImplTest {

    private static final Long FIRST_NOTE_ID = 701234567890123L;
    private static final Long SECOND_NOTE_ID = 701234567890124L;

    private NoteCountServiceImpl service;
    private NoteCountDOMapper noteCountDOMapper;
    private RedisTemplate<String, Object> redisTemplate;
    private HashOperations<String, Object, Object> hashOperations;
    private RedisOperations<String, Object> redisOperations;
    private List<List<Object>> readResults;
    private int pipelineCall;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        service = new NoteCountServiceImpl();
        noteCountDOMapper = mock(NoteCountDOMapper.class);
        redisTemplate = mock(RedisTemplate.class);
        hashOperations = mock(HashOperations.class);
        redisOperations = mock(RedisOperations.class);

        ReflectionTestUtils.setField(service, "noteCountDOMapper", noteCountDOMapper);
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        when(redisOperations.opsForHash()).thenReturn(hashOperations);
        doAnswer(invocation -> {
            SessionCallback callback = invocation.getArgument(0);
            callback.execute(redisOperations);
            return pipelineCall++ == 0 ? readResults : List.of();
        }).when(redisTemplate).executePipelined(any(SessionCallback.class));
    }

    @Test
    void cachedCountsAreReturnedInRequestOrderWithoutDatabaseReads() {
        readResults = List.of(Arrays.<Object>asList(5, 2, 9), Arrays.<Object>asList(1L, 0L, 3L));

        Response<List<FindNoteCountsByIdRspDTO>> response = service.findNotesCountData(
                request(FIRST_NOTE_ID, SECOND_NOTE_ID));

        assertEquals(List.of(FIRST_NOTE_ID, SECOND_NOTE_ID),
                response.getData().stream().map(FindNoteCountsByIdRspDTO::getNoteId).toList());
        assertEquals(5L, response.getData().get(0).getLikeTotal());
        assertEquals(2L, response.getData().get(0).getCollectTotal());
        assertEquals(9L, response.getData().get(0).getCommentTotal());
        assertEquals(3L, response.getData().get(1).getCommentTotal());
        verifyNoInteractions(noteCountDOMapper);
        verify(redisTemplate).executePipelined(any(SessionCallback.class));
        verify(hashOperations, never()).putAll(anyString(), anyMap());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void partialCacheUsesCachedFieldsAndBackfillsOnlyMissingFieldsFromDatabase() {
        readResults = List.of(Arrays.<Object>asList(5L, null, 9L), Arrays.<Object>asList(1L, 2L, 3L));
        when(noteCountDOMapper.selectByNoteIds(List.of(FIRST_NOTE_ID))).thenReturn(List.of(NoteCountDO.builder()
                .noteId(FIRST_NOTE_ID).likeTotal(50L).collectTotal(20L).commentTotal(90L).build()));

        Response<List<FindNoteCountsByIdRspDTO>> response = service.findNotesCountData(
                request(FIRST_NOTE_ID, SECOND_NOTE_ID));

        FindNoteCountsByIdRspDTO first = response.getData().get(0);
        assertEquals(5L, first.getLikeTotal());
        assertEquals(20L, first.getCollectTotal());
        assertEquals(9L, first.getCommentTotal());
        assertEquals(1L, response.getData().get(1).getLikeTotal());
        verify(noteCountDOMapper).selectByNoteIds(List.of(FIRST_NOTE_ID));
        verify(redisTemplate, org.mockito.Mockito.times(2)).executePipelined(any(SessionCallback.class));
        verify(hashOperations).putAll("count:note:" + FIRST_NOTE_ID, Map.of("collectTotal", 20L));
        verify(hashOperations, never()).putAll(eq("count:note:" + SECOND_NOTE_ID), anyMap());
        verify(redisOperations).expire(eq("count:note:" + FIRST_NOTE_ID),
                longThat(seconds -> seconds >= 1800 && seconds <= 3599), eq(TimeUnit.SECONDS));
    }

    @Test
    void missingCacheCountsAreLoadedFromTheDatabaseAndAllFieldsAreCached() {
        readResults = List.of(Arrays.<Object>asList(null, null, null));
        when(noteCountDOMapper.selectByNoteIds(List.of(FIRST_NOTE_ID))).thenReturn(List.of(NoteCountDO.builder()
                .noteId(FIRST_NOTE_ID).likeTotal(10L).collectTotal(4L).commentTotal(2L).build()));

        Response<List<FindNoteCountsByIdRspDTO>> response = service.findNotesCountData(request(FIRST_NOTE_ID));

        FindNoteCountsByIdRspDTO result = response.getData().get(0);
        assertEquals(10L, result.getLikeTotal());
        assertEquals(4L, result.getCollectTotal());
        assertEquals(2L, result.getCommentTotal());
        verify(hashOperations).putAll("count:note:" + FIRST_NOTE_ID,
                Map.of("likeTotal", 10L, "collectTotal", 4L, "commentTotal", 2L));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void missingDatabaseRowsReturnZeroAndAreCachedAsZeroWhenBatchHasOtherRows() {
        readResults = List.of(Arrays.<Object>asList(null, null, null), Arrays.<Object>asList(null, null, null));
        when(noteCountDOMapper.selectByNoteIds(List.of(FIRST_NOTE_ID, SECOND_NOTE_ID)))
                .thenReturn(List.of(NoteCountDO.builder()
                        .noteId(FIRST_NOTE_ID).likeTotal(10L).collectTotal(4L).commentTotal(2L).build()));

        Response<List<FindNoteCountsByIdRspDTO>> response = service.findNotesCountData(
                request(FIRST_NOTE_ID, SECOND_NOTE_ID));

        assertEquals(10L, response.getData().get(0).getLikeTotal());
        assertEquals(0L, response.getData().get(1).getLikeTotal());
        assertEquals(0L, response.getData().get(1).getCollectTotal());
        assertEquals(0L, response.getData().get(1).getCommentTotal());
        verify(hashOperations).putAll("count:note:" + SECOND_NOTE_ID,
                Map.of("likeTotal", 0L, "collectTotal", 0L, "commentTotal", 0L));
    }

    private FindNoteCountsByIdsReqDTO request(Long... noteIds) {
        return FindNoteCountsByIdsReqDTO.builder().noteIds(List.of(noteIds)).build();
    }
}
