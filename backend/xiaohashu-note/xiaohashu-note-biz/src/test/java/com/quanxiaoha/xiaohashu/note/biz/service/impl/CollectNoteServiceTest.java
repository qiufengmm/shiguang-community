package com.quanxiaoha.xiaohashu.note.biz.service.impl;

import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.note.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.note.biz.controller.NoteController;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteCollectionDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteCollectionDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteLikeDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.enums.ResponseCodeEnum;
import com.quanxiaoha.xiaohashu.note.biz.service.NoteIdSetCache;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.CollectNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.CollectUnCollectNoteMqDTO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.UnCollectNoteReqVO;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.Message;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectNoteServiceTest {

    private static final Long USER_ID = 701234567890123L;
    private static final Long NOTE_ID = 801234567890123L;

    private NoteDOMapper noteDOMapper;
    private NoteCollectionDOMapper noteCollectionDOMapper;
    private RedisTemplate<String, String> redisTemplate;
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;
    private ZSetOperations<String, String> zSetOperations;
    private RocketMQTemplate rocketMQTemplate;
    private NoteServiceImpl noteService;
    private NoteIdSetCache noteIdSetCache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        noteDOMapper = mock(NoteDOMapper.class);
        noteCollectionDOMapper = mock(NoteCollectionDOMapper.class);
        NoteLikeDOMapper noteLikeDOMapper = mock(NoteLikeDOMapper.class);
        redisTemplate = mock(RedisTemplate.class);
        threadPoolTaskExecutor = mock(ThreadPoolTaskExecutor.class);
        rocketMQTemplate = mock(RocketMQTemplate.class);
        SendResult defaultSendResult = mock(SendResult.class);
        when(defaultSendResult.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSendOrderly(anyString(), any(Message.class), anyString()))
                .thenReturn(defaultSendResult);
        noteIdSetCache = mock(NoteIdSetCache.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        zSetOperations = mock(ZSetOperations.class);

        noteService = new NoteServiceImpl();
        ReflectionTestUtils.setField(noteService, "noteDOMapper", noteDOMapper);
        ReflectionTestUtils.setField(noteService, "noteCollectionDOMapper", noteCollectionDOMapper);
        ReflectionTestUtils.setField(noteService, "noteLikeDOMapper", noteLikeDOMapper);
        ReflectionTestUtils.setField(noteService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(noteService, "threadPoolTaskExecutor", threadPoolTaskExecutor);
        ReflectionTestUtils.setField(noteService, "rocketMQTemplate", rocketMQTemplate);
        ReflectionTestUtils.setField(noteService, "noteIdSetCache", noteIdSetCache);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("{\"id\":801234567890123,\"creatorId\":901234567890123,\"visible\":0}");
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        doReturn(null).when(zSetOperations).score(anyString(), any(Long.class));
        when(noteIdSetCache.contains(anyString(), anyString(), anyLong())).thenReturn(0L);
        when(noteIdSetCache.addIfReady(anyString(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(1L);
        when(noteIdSetCache.removeIfReady(anyString(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(1L);
        when(noteIdSetCache.currentVersion(anyString())).thenReturn("0");
        when(noteIdSetCache.rebuildIfVersionMatches(anyString(), anyString(), anyString(), anyString(), any(),
                anyLong())).thenReturn(true);
        when(noteDOMapper.selectCreatorIdByNoteId(NOTE_ID)).thenReturn(901234567890123L);
        LoginUserContextHolder.setUserId(USER_ID);
    }

    @AfterEach
    void clearContext() {
        LoginUserContextHolder.remove();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldBackfillBloomAndAddTargetWhenBloomIsMissing() {
        when(noteIdSetCache.contains(anyString(), anyString(), anyLong())).thenReturn(-1L);
        when(noteCollectionDOMapper.selectCountByUserIdAndNoteId(USER_ID, NOTE_ID)).thenReturn(0);
        when(noteCollectionDOMapper.selectByUserId(USER_ID)).thenReturn(List.of(NoteCollectionDO.builder()
                .userId(USER_ID)
                .noteId(NOTE_ID + 1)
                .status(1)
                .build()));

        Response<?> response = noteService.collectNote(CollectNoteReqVO.builder().id(NOTE_ID).build());

        assertNotNull(response);
        verify(noteCollectionDOMapper).selectByUserId(USER_ID);
        verify(noteIdSetCache).rebuildIfVersionMatches(anyString(), anyString(), anyString(), anyString(), any(),
                anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectExistingCollectionWhenBloomIsMissingAndRebuildInBackground() {
        when(noteIdSetCache.contains(anyString(), anyString(), anyLong())).thenReturn(-1L);
        when(noteCollectionDOMapper.selectCountByUserIdAndNoteId(USER_ID, NOTE_ID)).thenReturn(1);

        BizException exception = assertThrows(BizException.class,
                () -> noteService.collectNote(CollectNoteReqVO.builder().id(NOTE_ID).build()));

        assertEquals(ResponseCodeEnum.NOTE_ALREADY_COLLECTED.getErrorCode(), exception.getErrorCode());
        verify(noteIdSetCache).rebuildIfVersionMatches(anyString(), anyString(), anyString(), anyString(), any(),
                anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectCollectionWhenRoaringBitmapConfirmsAlreadyCollected() {
        when(noteIdSetCache.contains(anyString(), anyString(), anyLong())).thenReturn(1L);

        BizException exception = assertThrows(BizException.class,
                () -> noteService.collectNote(CollectNoteReqVO.builder().id(NOTE_ID).build()));

        assertEquals(ResponseCodeEnum.NOTE_ALREADY_COLLECTED.getErrorCode(), exception.getErrorCode());
        verify(noteCollectionDOMapper, never()).selectNoteIsCollected(USER_ID, NOTE_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectCollectionWhenRoaringBitmapMatchesWithoutDatabaseFallback() {
        when(noteIdSetCache.contains(anyString(), anyString(), anyLong())).thenReturn(1L);

        BizException exception = assertThrows(BizException.class,
                () -> noteService.collectNote(CollectNoteReqVO.builder().id(NOTE_ID).build()));

        assertEquals(ResponseCodeEnum.NOTE_ALREADY_COLLECTED.getErrorCode(), exception.getErrorCode());
        verify(noteCollectionDOMapper, never()).selectNoteIsCollected(USER_ID, NOTE_ID);
        verify(threadPoolTaskExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldAllowCollectionWhenRoaringBitmapDoesNotContainTheNote() {
        doReturn(0L, 0L).when(redisTemplate).execute(any(DefaultRedisScript.class), anyList(), any(Object.class));

        Response<?> response = noteService.collectNote(CollectNoteReqVO.builder().id(NOTE_ID).build());

        assertNotNull(response);
        verify(noteCollectionDOMapper, never()).selectNoteIsCollected(USER_ID, NOTE_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldInitializeZSetWithCurrentNoteWhenNoCollectionHistoryExists() {
        when(noteCollectionDOMapper.selectCountByUserIdAndNoteId(USER_ID, NOTE_ID)).thenReturn(0);
        when(noteCollectionDOMapper.selectByUserId(USER_ID)).thenReturn(List.of());
        when(noteCollectionDOMapper.selectCollectedByUserIdAndLimit(USER_ID, 300)).thenReturn(List.of());
        doReturn(-1L, 0L, -1L, 0L).when(redisTemplate)
                .execute(any(DefaultRedisScript.class), anyList(), any(Object.class));

        Response<?> response = noteService.collectNote(CollectNoteReqVO.builder().id(NOTE_ID).build());

        assertNotNull(response);
        verify(noteCollectionDOMapper).selectCollectedByUserIdAndLimit(USER_ID, 300);
        verify(redisTemplate, times(2)).execute(any(DefaultRedisScript.class), anyList(), any(Object.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRestoreCollectionHistoryBeforeAddingCurrentNoteToZSet() {
        when(noteCollectionDOMapper.selectCountByUserIdAndNoteId(USER_ID, NOTE_ID)).thenReturn(0);
        when(noteCollectionDOMapper.selectByUserId(USER_ID)).thenReturn(List.of());
        when(noteCollectionDOMapper.selectCollectedByUserIdAndLimit(USER_ID, 300)).thenReturn(List.of(
                NoteCollectionDO.builder().noteId(NOTE_ID + 1).createTime(LocalDateTime.now()).build()));
        doReturn(-1L, 0L, -1L, 0L, 0L).when(redisTemplate)
                .execute(any(DefaultRedisScript.class), anyList(), any(Object.class));

        Response<?> response = noteService.collectNote(CollectNoteReqVO.builder().id(NOTE_ID).build());

        assertNotNull(response);
        verify(noteCollectionDOMapper).selectCollectedByUserIdAndLimit(USER_ID, 300);
        verify(redisTemplate, times(3)).execute(any(DefaultRedisScript.class), anyList(), any(Object.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSendCollectMessageInUserOrderAfterUpdatingZSet() {
        when(noteCollectionDOMapper.selectCountByUserIdAndNoteId(USER_ID, NOTE_ID)).thenReturn(0);
        when(noteCollectionDOMapper.selectByUserId(USER_ID)).thenReturn(List.of());
        when(noteCollectionDOMapper.selectCollectedByUserIdAndLimit(USER_ID, 300)).thenReturn(List.of());
        doReturn(-1L, 0L, -1L, 0L).when(redisTemplate)
                .execute(any(DefaultRedisScript.class), anyList(), any(Object.class));
        SendResult sendResult = mock(SendResult.class);
        when(sendResult.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSendOrderly(anyString(), any(Message.class), anyString())).thenReturn(sendResult);

        noteService.collectNote(CollectNoteReqVO.builder().id(NOTE_ID).build());

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).syncSendOrderly(eq("CollectUnCollectTopic:Collect"), captor.capture(),
                eq(USER_ID.toString()));
        CollectUnCollectNoteMqDTO dto = JsonUtils.parseObject((String) captor.getValue().getPayload(),
                CollectUnCollectNoteMqDTO.class);
        assertEquals(901234567890123L, dto.getNoteCreatorId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectUncollectWhenRoaringBitmapConfirmsTheNoteWasNotCollected() {
        when(noteIdSetCache.contains(anyString(), anyString(), anyLong())).thenReturn(0L);

        BizException exception = assertThrows(BizException.class,
                () -> noteService.unCollectNote(UnCollectNoteReqVO.builder().id(NOTE_ID).build()));

        assertEquals(ResponseCodeEnum.NOTE_NOT_COLLECTED.getErrorCode(), exception.getErrorCode());
        verify(noteCollectionDOMapper, never()).selectCountByUserIdAndNoteId(USER_ID, NOTE_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRebuildMissingRoaringBitmapAndRemoveZSetEntryWhenDatabaseConfirmsCollection() {
        when(noteIdSetCache.contains(anyString(), anyString(), anyLong())).thenReturn(-1L);
        when(noteCollectionDOMapper.selectCountByUserIdAndNoteId(USER_ID, NOTE_ID)).thenReturn(1);

        Response<?> response = noteService.unCollectNote(UnCollectNoteReqVO.builder().id(NOTE_ID).build());

        assertNotNull(response);
        verify(noteIdSetCache).rebuildIfVersionMatches(anyString(), anyString(), anyString(), anyString(), any(),
                anyLong());
        verify(zSetOperations).remove(RedisKeyConstants.buildUserNoteCollectZSetKey(USER_ID), NOTE_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRemoveZSetEntryWhenRoaringBitmapConfirmsCollection() {
        when(noteIdSetCache.contains(anyString(), anyString(), anyLong())).thenReturn(1L);

        Response<?> response = noteService.unCollectNote(UnCollectNoteReqVO.builder().id(NOTE_ID).build());

        assertNotNull(response);
        verify(zSetOperations).remove(RedisKeyConstants.buildUserNoteCollectZSetKey(USER_ID), NOTE_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSendUncollectMessageInUserOrderAfterUpdatingZSet() {
        when(noteIdSetCache.contains(anyString(), anyString(), anyLong())).thenReturn(1L);
        SendResult sendResult = mock(SendResult.class);
        when(sendResult.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSendOrderly(anyString(), any(Message.class), anyString())).thenReturn(sendResult);

        noteService.unCollectNote(UnCollectNoteReqVO.builder().id(NOTE_ID).build());

        verify(rocketMQTemplate).syncSendOrderly(eq("CollectUnCollectTopic:UnCollect"), any(Message.class),
                eq(USER_ID.toString()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void invalidatesTheCollectionCacheWhenTheBrokerRejectsTheEvent() {
        when(noteIdSetCache.contains(anyString(), anyString(), anyLong())).thenReturn(0L);
        when(noteIdSetCache.addIfReady(anyString(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(1L);
        when(rocketMQTemplate.syncSendOrderly(anyString(), any(Message.class), anyString()))
                .thenThrow(new IllegalStateException("broker unavailable"));

        assertThrows(BizException.class,
                () -> noteService.collectNote(CollectNoteReqVO.builder().id(NOTE_ID).build()));

        verify(noteIdSetCache).invalidate(anyString(), anyString(), anyString(), anyLong());
        verify(redisTemplate).delete(RedisKeyConstants.buildUserNoteCollectZSetKey(USER_ID));
    }

    @Test
    void shouldExposeCollectEndpoint() {
        assertTrue(Arrays.stream(NoteController.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("collectNote")));
    }
}
