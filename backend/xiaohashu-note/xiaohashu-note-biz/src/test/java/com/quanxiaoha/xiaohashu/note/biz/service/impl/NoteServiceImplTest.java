package com.quanxiaoha.xiaohashu.note.biz.service.impl;

import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.note.biz.controller.NoteController;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteContentSagaDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteLikeDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteLikeDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.enums.ResponseCodeEnum;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindPublishedNoteListReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindPublishedNoteListRspVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.NoteItemRspVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.DeleteNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.LikeNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.PublishNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.UpdateNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.UpdateNoteVisibleOnlyMeReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.LikeUnlikeNoteMqDTO;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.PublishNoteDTO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.UnlikeNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.rpc.CountRpcService;
import com.quanxiaoha.xiaohashu.note.biz.rpc.DistributedIdGeneratorRpcService;
import com.quanxiaoha.xiaohashu.note.biz.rpc.KeyValueRpcService;
import com.quanxiaoha.xiaohashu.note.biz.rpc.UserRpcService;
import com.quanxiaoha.xiaohashu.note.biz.service.NoteIdSetCache;
import com.quanxiaoha.xiaohashu.note.biz.service.NoteOperatePersistenceService;
import com.quanxiaoha.xiaohashu.note.biz.service.NoteOperateOutboxService;
import com.quanxiaoha.xiaohashu.note.biz.service.NoteContentSagaService;
import com.quanxiaoha.xiaohashu.count.dto.FindNoteCountsByIdRspDTO;
import com.quanxiaoha.xiaohashu.user.dto.resp.FindUserByIdRspDTO;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.Message;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NoteServiceImplTest {

    private static final Long USER_ID = 701234567890123L;
    private static final Long NOTE_ID = 801234567890123L;

    private NoteDOMapper noteDOMapper;
    private NoteLikeDOMapper noteLikeDOMapper;
    private List<NoteLikeDO> batchNoteLikes = List.of();
    private CountRpcService countRpcService;
    private DistributedIdGeneratorRpcService distributedIdGeneratorRpcService;
    private KeyValueRpcService keyValueRpcService;
    private UserRpcService userRpcService;
    private RedisTemplate<String, String> redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RocketMQTemplate rocketMQTemplate;
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;
    private NoteIdSetCache noteIdSetCache;
    private NoteOperatePersistenceService noteOperatePersistenceService;
    private NoteOperateOutboxService noteOperateOutboxService;
    private NoteContentSagaService noteContentSagaService;

    private NoteServiceImpl noteService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        noteDOMapper = mock(NoteDOMapper.class);
        batchNoteLikes = List.of();
        noteLikeDOMapper = mock(NoteLikeDOMapper.class, invocation -> {
            if ("selectByUserIdAndNoteIds".equals(invocation.getMethod().getName())) {
                return batchNoteLikes;
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        countRpcService = mock(CountRpcService.class);
        distributedIdGeneratorRpcService = mock(DistributedIdGeneratorRpcService.class);
        keyValueRpcService = mock(KeyValueRpcService.class);
        userRpcService = mock(UserRpcService.class);
        redisTemplate = mock(RedisTemplate.class);
        rocketMQTemplate = mock(RocketMQTemplate.class);
        SendResult defaultSendResult = mock(SendResult.class);
        when(defaultSendResult.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSendOrderly(anyString(), any(Message.class), anyString()))
                .thenReturn(defaultSendResult);
        noteIdSetCache = mock(NoteIdSetCache.class);
        noteOperatePersistenceService = mock(NoteOperatePersistenceService.class);
        noteOperateOutboxService = mock(NoteOperateOutboxService.class);
        noteContentSagaService = mock(NoteContentSagaService.class);
        when(noteContentSagaService.lockLeaseForCommit(any())).thenReturn(true);
        valueOperations = mock(ValueOperations.class);
        ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);

        noteService = new NoteServiceImpl();
        ReflectionTestUtils.setField(noteService, "noteDOMapper", noteDOMapper);
        ReflectionTestUtils.setField(noteService, "noteLikeDOMapper", noteLikeDOMapper);
        ReflectionTestUtils.setField(noteService, "distributedIdGeneratorRpcService", distributedIdGeneratorRpcService);
        ReflectionTestUtils.setField(noteService, "keyValueRpcService", keyValueRpcService);
        ReflectionTestUtils.setField(noteService, "userRpcService", userRpcService);
        ReflectionTestUtils.setField(noteService, "countRpcService", countRpcService);
        ReflectionTestUtils.setField(noteService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(noteService, "rocketMQTemplate", rocketMQTemplate);
        ReflectionTestUtils.setField(noteService, "noteIdSetCache", noteIdSetCache);
        ReflectionTestUtils.setField(noteService, "noteOperatePersistenceService", noteOperatePersistenceService);
        ReflectionTestUtils.setField(noteService, "noteOperateOutboxService", noteOperateOutboxService);
        ReflectionTestUtils.setField(noteService, "noteContentSagaService", noteContentSagaService);
        when(noteOperatePersistenceService.softDeleteNote(any(), any())).thenReturn(true);
        when(noteDOMapper.updateByPrimaryKey(any(NoteDO.class))).thenReturn(1);
        when(noteDOMapper.updateContentWithExpectedUuid(any(NoteDO.class), any())).thenReturn(1);
        threadPoolTaskExecutor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(threadPoolTaskExecutor).execute(any(Runnable.class));
        ReflectionTestUtils.setField(noteService, "threadPoolTaskExecutor", threadPoolTaskExecutor);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(noteIdSetCache.contains(anyString(), anyString(), anyLong())).thenReturn(0L);
        when(noteIdSetCache.containsAll(anyString(), anyString(), any())).thenReturn(List.of(0L));
        when(noteIdSetCache.addIfReady(anyString(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(1L);
        when(noteIdSetCache.removeIfReady(anyString(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(1L);
        when(noteIdSetCache.currentVersion(anyString())).thenReturn("0");
        when(noteIdSetCache.rebuildIfVersionMatches(anyString(), anyString(), anyString(), anyString(), any(),
                anyLong())).thenReturn(true);
        doReturn(null).when(zSetOperations).score(anyString(), anyLong());
        when(noteDOMapper.selectCreatorIdByNoteId(NOTE_ID)).thenReturn(901234567890123L);
        LoginUserContextHolder.setUserId(USER_ID);
    }

    @AfterEach
    void clearContext() {
        LoginUserContextHolder.remove();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldAllowLikeWhenRoaringBitmapDoesNotContainTheNote() {
        doReturn(0L, 0L).when(redisTemplate).execute(any(DefaultRedisScript.class), anyList(), any(Object.class));

        Response<?> response = noteService.likeNote(LikeNoteReqVO.builder().id(NOTE_ID).build());

        assertNotNull(response);
        verify(noteLikeDOMapper, never()).selectNoteIsLiked(USER_ID, NOTE_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectLikeWhenRoaringBitmapConfirmsAlreadyLikedWithoutDatabaseFallback() {
        when(noteIdSetCache.contains(anyString(), anyString(), anyLong())).thenReturn(1L);
        doReturn(1L).when(redisTemplate).execute(any(DefaultRedisScript.class), anyList(), any(Object.class));

        BizException exception = assertThrows(BizException.class,
                () -> noteService.likeNote(LikeNoteReqVO.builder().id(NOTE_ID).build()));

        assertEquals(ResponseCodeEnum.NOTE_ALREADY_LIKED.getErrorCode(), exception.getErrorCode());
        verify(noteLikeDOMapper, never()).selectNoteIsLiked(USER_ID, NOTE_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSendOrderlyLikeMessageAfterSuccessfulLike() {
        doReturn(0L, 0L).when(redisTemplate).execute(any(DefaultRedisScript.class), anyList(), any(Object.class));
        SendResult sendResult = mock(SendResult.class);
        when(sendResult.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSendOrderly(anyString(), any(Message.class), anyString())).thenReturn(sendResult);

        noteService.likeNote(LikeNoteReqVO.builder().id(NOTE_ID).build());

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).syncSendOrderly(eq("LikeUnlikeTopic:Like"), captor.capture(),
                eq(USER_ID.toString()));
        LikeUnlikeNoteMqDTO dto = JsonUtils.parseObject((String) captor.getValue().getPayload(),
                LikeUnlikeNoteMqDTO.class);
        assertEquals(901234567890123L, dto.getNoteCreatorId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldBackfillExistingLikesWhenRoaringBitmapDoesNotExist() {
        when(noteIdSetCache.contains(anyString(), anyString(), anyLong())).thenReturn(-1L);
        when(noteLikeDOMapper.selectByUserId(USER_ID)).thenReturn(List.of(NoteLikeDO.builder()
                .userId(USER_ID)
                .noteId(NOTE_ID + 1)
                .createTime(LocalDateTime.now())
                .status(1)
                .build()));
        doReturn(-1L).when(redisTemplate).execute(any(DefaultRedisScript.class), anyList(), any(Object.class));

        noteService.likeNote(LikeNoteReqVO.builder().id(NOTE_ID).build());

        verify(noteLikeDOMapper).selectByUserId(USER_ID);
    }

    @Test
    void shouldExposeUnlikeControllerMethod() {
        assertTrue(Arrays.stream(NoteController.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("unlikeNote")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void invalidatesTheLikeCacheWhenTheBrokerRejectsTheEvent() {
        doReturn(0L, 0L).when(redisTemplate).execute(any(DefaultRedisScript.class), anyList(), any(Object.class));
        when(rocketMQTemplate.syncSendOrderly(anyString(), any(Message.class), anyString()))
                .thenThrow(new IllegalStateException("broker unavailable"));

        assertThrows(BizException.class, () -> noteService.likeNote(LikeNoteReqVO.builder().id(NOTE_ID).build()));

        verify(noteIdSetCache).invalidate(anyString(), anyString(), anyString(), anyLong());
        verify(redisTemplate).delete(com.quanxiaoha.xiaohashu.note.biz.constant.RedisKeyConstants
                .buildUserNoteLikeZSetKey(USER_ID));
    }

    @Test
    void shouldExposeLikeAndCollectionStatusControllerMethod() {
        assertTrue(Arrays.stream(NoteController.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("isLikedAndCollectedData")));
    }

    @Test
    void shouldMapPublishedNotesAndUseTheOldestNoteAsNextCursor() {
        Long olderNoteId = NOTE_ID - 1;
        when(noteDOMapper.selectPublishedNoteListByUserIdAndCursor(USER_ID, null)).thenReturn(List.of(
                NoteDO.builder().id(NOTE_ID).creatorId(USER_ID).type(0).title("图文标题")
                        .imgUris("https://cdn.example/cover.jpg,https://cdn.example/second.jpg").build(),
                NoteDO.builder().id(olderNoteId).creatorId(USER_ID).type(1).title("视频标题")
                        .videoUri("https://cdn.example/video.mp4").build()));
        when(userRpcService.findById(USER_ID)).thenReturn(FindUserByIdRspDTO.builder()
                .id(USER_ID).nickName("作者昵称").avatar("https://cdn.example/avatar.jpg").build());
        when(countRpcService.findByNoteIds(List.of(NOTE_ID, olderNoteId))).thenReturn(List.of(
                FindNoteCountsByIdRspDTO.builder().noteId(NOTE_ID).likeTotal(123L).build()));
        doReturn(List.of(-1L)).when(redisTemplate).execute(any(DefaultRedisScript.class), anyList(),
                any(Object.class), any(Object.class));

        Response<?> response = noteService.findPublishedNoteList(
                FindPublishedNoteListReqVO.builder().userId(USER_ID).build());

        assertTrue(response.isSuccess());
        FindPublishedNoteListRspVO data = (FindPublishedNoteListRspVO) response.getData();
        assertEquals(2, data.getNotes().size());
        assertEquals(NOTE_ID, data.getNotes().get(0).getNoteId());
        assertEquals("https://cdn.example/cover.jpg", data.getNotes().get(0).getCover());
        assertEquals("https://cdn.example/video.mp4", data.getNotes().get(1).getVideoUri());
        assertEquals("作者昵称", data.getNotes().get(0).getNickname());
        assertEquals("https://cdn.example/avatar.jpg", data.getNotes().get(1).getAvatar());
        assertEquals("123", data.getNotes().get(0).getLikeTotal());
        assertEquals("0", data.getNotes().get(1).getLikeTotal());
        assertTrue(JsonUtils.toJsonString(data.getNotes().get(0)).contains("\"isLiked\":false"));
        assertTrue(JsonUtils.toJsonString(data.getNotes().get(1)).contains("\"isLiked\":false"));
        assertEquals(olderNoteId, data.getNextCursor());
        verify(noteDOMapper).selectPublishedNoteListByUserIdAndCursor(USER_ID, null);
        verify(userRpcService).findById(USER_ID);
        verify(countRpcService).findByNoteIds(List.of(NOTE_ID, olderNoteId));
    }

    @Test
    void shouldReturnCachedFirstPageSortedByNoteIdAndUseOldestAsNextCursor() {
        LoginUserContextHolder.setUserId(USER_ID + 1);
        Long olderNoteId = NOTE_ID - 1;
        String cacheKey = "note:published:list:" + USER_ID;
        when(valueOperations.get(cacheKey)).thenReturn(JsonUtils.toJsonString(List.of(
                NoteItemRspVO.builder().noteId(olderNoteId).title("旧笔记").build(),
                NoteItemRspVO.builder().noteId(NOTE_ID).title("新笔记").build())));
        doReturn(List.of(0L, 0L)).when(redisTemplate).execute(any(DefaultRedisScript.class), anyList(),
                any(Object.class), any(Object.class));

        Response<?> response = noteService.findPublishedNoteList(
                FindPublishedNoteListReqVO.builder().userId(USER_ID).build());

        assertTrue(response.isSuccess());
        FindPublishedNoteListRspVO data = (FindPublishedNoteListRspVO) response.getData();
        assertEquals(List.of(NOTE_ID, olderNoteId), data.getNotes().stream().map(NoteItemRspVO::getNoteId).toList());
        assertEquals(olderNoteId, data.getNextCursor());
        verify(noteDOMapper, never()).selectPublishedNoteListByUserIdAndCursor(anyLong(), any());
        verify(userRpcService, never()).findById(anyLong());
        verify(countRpcService, never()).findByNoteIds(anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnCachedLikeStatesAndRefreshLikeTotalsForTheAuthor() {
        Long olderNoteId = NOTE_ID - 1;
        when(valueOperations.get("note:published:list:" + USER_ID)).thenReturn(JsonUtils.toJsonString(List.of(
                NoteItemRspVO.builder().noteId(NOTE_ID).likeTotal("2").build(),
                NoteItemRspVO.builder().noteId(olderNoteId).likeTotal("3").build())));
        doReturn(List.of(FindNoteCountsByIdRspDTO.builder().noteId(NOTE_ID).likeTotal(125L).build(),
                FindNoteCountsByIdRspDTO.builder().noteId(olderNoteId).likeTotal(57L).build()))
                .when(countRpcService).findByNoteIds(List.of(NOTE_ID, olderNoteId));
        when(noteIdSetCache.containsAll(anyString(), anyString(), any())).thenReturn(List.of(1L, 0L));

        Response<?> response = noteService.findPublishedNoteList(
                FindPublishedNoteListReqVO.builder().userId(USER_ID).build());

        FindPublishedNoteListRspVO data = (FindPublishedNoteListRspVO) response.getData();
        assertTrue(JsonUtils.toJsonString(data.getNotes().get(0)).contains("\"isLiked\":true"));
        assertTrue(JsonUtils.toJsonString(data.getNotes().get(1)).contains("\"isLiked\":false"));
        assertEquals("125", data.getNotes().get(0).getLikeTotal());
        assertEquals("57", data.getNotes().get(1).getLikeTotal());
        verify(countRpcService).findByNoteIds(List.of(NOTE_ID, olderNoteId));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldKeepCachedLikeTotalsForAnotherViewerAndStillSetLikeStates() {
        LoginUserContextHolder.setUserId(USER_ID + 1);
        when(valueOperations.get("note:published:list:" + USER_ID)).thenReturn(JsonUtils.toJsonString(List.of(
                NoteItemRspVO.builder().noteId(NOTE_ID).likeTotal("8").build())));
        when(noteIdSetCache.containsAll(anyString(), anyString(), any())).thenReturn(List.of(1L));

        Response<?> response = noteService.findPublishedNoteList(
                FindPublishedNoteListReqVO.builder().userId(USER_ID).build());

        FindPublishedNoteListRspVO data = (FindPublishedNoteListRspVO) response.getData();
        assertTrue(JsonUtils.toJsonString(data.getNotes().get(0)).contains("\"isLiked\":true"));
        assertEquals("8", data.getNotes().get(0).getLikeTotal());
        verify(countRpcService, never()).findByNoteIds(anyList());
    }

    @Test
    void shouldNotExposeCachedLikeStateToAnAnonymousViewer() {
        LoginUserContextHolder.remove();
        when(valueOperations.get("note:published:list:" + USER_ID)).thenReturn(JsonUtils.toJsonString(List.of(
                NoteItemRspVO.builder().noteId(NOTE_ID).isLiked(true).build())));

        Response<?> response = noteService.findPublishedNoteList(
                FindPublishedNoteListReqVO.builder().userId(USER_ID).build());

        FindPublishedNoteListRspVO data = (FindPublishedNoteListRspVO) response.getData();
        assertTrue(response.isSuccess());
        assertTrue(JsonUtils.toJsonString(data.getNotes().get(0)).contains("\"isLiked\":false"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldLoadLikeStatesFromDatabaseWhenUserBitmapIsMissing() {
        Long olderNoteId = NOTE_ID - 1;
        batchNoteLikes = List.of(NoteLikeDO.builder().userId(USER_ID).noteId(NOTE_ID).status(1).build());
        when(noteDOMapper.selectPublishedNoteListByUserIdAndCursor(USER_ID, null)).thenReturn(List.of(
                NoteDO.builder().id(NOTE_ID).creatorId(USER_ID).type(0).title("已点赞笔记").build(),
                NoteDO.builder().id(olderNoteId).creatorId(USER_ID).type(0).title("未点赞笔记").build()));
        when(userRpcService.findById(USER_ID)).thenReturn(null);
        when(countRpcService.findByNoteIds(List.of(NOTE_ID, olderNoteId))).thenReturn(List.of());
        when(noteIdSetCache.containsAll(anyString(), anyString(), any())).thenReturn(List.of(-1L));

        Response<?> response = noteService.findPublishedNoteList(
                FindPublishedNoteListReqVO.builder().userId(USER_ID).build());

        FindPublishedNoteListRspVO data = (FindPublishedNoteListRspVO) response.getData();
        assertTrue(JsonUtils.toJsonString(data.getNotes().get(0)).contains("\"isLiked\":true"));
        assertTrue(JsonUtils.toJsonString(data.getNotes().get(1)).contains("\"isLiked\":false"));
        var queryInvocation = org.mockito.Mockito.mockingDetails(noteLikeDOMapper).getInvocations().stream()
                .filter(invocation -> "selectByUserIdAndNoteIds".equals(invocation.getMethod().getName()))
                .findFirst().orElseThrow();
        assertEquals(USER_ID, queryInvocation.getArgument(0));
        assertEquals(List.of(NOTE_ID, olderNoteId), queryInvocation.getArgument(1));
    }

    @Test
    void shouldCacheDatabaseFirstPageAsJsonWithRandomExpiryUnderOneHour() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return CompletableFuture.completedFuture(null);
        }).when(threadPoolTaskExecutor).submit(any(Runnable.class));
        when(noteDOMapper.selectPublishedNoteListByUserIdAndCursor(USER_ID, null)).thenReturn(List.of(
                NoteDO.builder().id(NOTE_ID).creatorId(USER_ID).type(0).title("新笔记").build()));
        when(userRpcService.findById(USER_ID)).thenReturn(null);
        when(countRpcService.findByNoteIds(List.of(NOTE_ID))).thenReturn(List.of());
        doReturn(List.of(-1L)).when(redisTemplate).execute(any(DefaultRedisScript.class), anyList(),
                any(Object.class));

        noteService.findPublishedNoteList(FindPublishedNoteListReqVO.builder().userId(USER_ID).build());

        ArgumentCaptor<Long> expireSecondsCaptor = ArgumentCaptor.forClass(Long.class);
        verify(valueOperations).set(eq("note:published:list:" + USER_ID), anyString(),
                expireSecondsCaptor.capture(), eq(TimeUnit.SECONDS));
        assertTrue(expireSecondsCaptor.getValue() >= 1800L && expireSecondsCaptor.getValue() < 3600L);
        verify(threadPoolTaskExecutor).submit(any(Runnable.class));
    }

    @Test
    void shouldDeletePublishedListCacheAndScheduleDelayedDeleteWhenPublishing() {
        when(distributedIdGeneratorRpcService.getSnowflakeId()).thenReturn("901234567890123");

        noteService.publishNote(PublishNoteReqVO.builder()
                .type(0)
                .imgUris(List.of("https://cdn.example/cover.jpg"))
                .title("新笔记")
                .build());

        verify(noteOperatePersistenceService).insertPublishedNote(any(NoteDO.class));
        verify(redisTemplate).delete("note:published:list:" + USER_ID);
        verify(rocketMQTemplate, never()).sendMessageInTransaction(anyString(), any(Message.class), isNull());
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).asyncSend(eq("DelayDeletePublishedNoteListRedisCacheTopic"),
                messageCaptor.capture(), any(SendCallback.class), eq(3000L), eq(1));
        assertEquals(USER_ID.toString(), messageCaptor.getValue().getPayload());
    }

    @Test
    void shouldSendNonEmptyNoteContentAsTransactionMessage() {
        when(distributedIdGeneratorRpcService.getSnowflakeId()).thenReturn(String.valueOf(NOTE_ID));
        TransactionSendResult transactionSendResult = mock(TransactionSendResult.class);
        when(transactionSendResult.getLocalTransactionState()).thenReturn(LocalTransactionState.COMMIT_MESSAGE);
        doReturn(transactionSendResult).when(rocketMQTemplate).sendMessageInTransaction(
                eq("PublishNoteTransactionTopic"), any(Message.class), isNull());

        Response<?> response = noteService.publishNote(PublishNoteReqVO.builder()
                .type(0)
                .imgUris(List.of("https://cdn.example/cover.jpg"))
                .title("事务消息笔记")
                .content("笔记正文")
                .build());

        assertTrue(response.isSuccess());
        verify(keyValueRpcService, never()).saveNoteContent(anyString(), anyString());
        verify(noteDOMapper, never()).insert(any(NoteDO.class));
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).sendMessageInTransaction(eq("PublishNoteTransactionTopic"), messageCaptor.capture(),
                isNull());
        PublishNoteDTO transactionNote = JsonUtils.parseObject((String) messageCaptor.getValue().getPayload(), PublishNoteDTO.class);
        assertEquals(NOTE_ID, transactionNote.getId());
        assertEquals(USER_ID, transactionNote.getCreatorId());
        assertEquals(false, transactionNote.getIsContentEmpty());
        assertEquals("笔记正文", transactionNote.getContent());
        assertNotNull(UUID.fromString(transactionNote.getContentUuid()));
    }

    @Test
    void shouldNotReportPublishSuccessWhenTransactionOutcomeIsUnknown() {
        when(distributedIdGeneratorRpcService.getSnowflakeId()).thenReturn(String.valueOf(NOTE_ID));
        TransactionSendResult transactionSendResult = mock(TransactionSendResult.class);
        when(transactionSendResult.getLocalTransactionState()).thenReturn(LocalTransactionState.UNKNOW);
        doReturn(transactionSendResult).when(rocketMQTemplate).sendMessageInTransaction(
                eq("PublishNoteTransactionTopic"), any(Message.class), isNull());

        BizException exception = assertThrows(BizException.class, () -> noteService.publishNote(PublishNoteReqVO.builder()
                .type(0)
                .imgUris(List.of("https://cdn.example/cover.jpg"))
                .title("结果未知笔记")
                .content("可重试正文")
                .build()));

        assertEquals(ResponseCodeEnum.NOTE_PUBLISH_FAIL.getErrorCode(), exception.getErrorCode());
    }

    @Test
    void shouldNotCacheNoteDetailWhenRequiredBodyIsUnavailable() {
        Long noteId = NOTE_ID + 987L;
        when(noteDOMapper.selectByPrimaryKey(noteId)).thenReturn(NoteDO.builder()
                .id(noteId)
                .isContentEmpty(false)
                .contentUuid("content-unavailable-test")
                .creatorId(USER_ID)
                .visible(0)
                .build());
        when(userRpcService.findById(USER_ID)).thenReturn(FindUserByIdRspDTO.builder()
                .id(USER_ID).nickName("fixture user").avatar("https://cdn.example/avatar.jpg").build());
        when(keyValueRpcService.findNoteContent("content-unavailable-test")).thenReturn(null);

        BizException exception = assertThrows(BizException.class, () -> noteService.findNoteDetail(
                com.quanxiaoha.xiaohashu.note.biz.model.vo.FindNoteDetailReqVO.builder().id(noteId).build()));

        assertEquals(ResponseCodeEnum.NOTE_CONTENT_NOT_READY.getErrorCode(), exception.getErrorCode());
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    void shouldReturnNoteDetailWhenCreatorProfileWasDeleted() {
        Long noteId = NOTE_ID + 988L;
        when(noteDOMapper.selectByPrimaryKey(noteId)).thenReturn(NoteDO.builder()
                .id(noteId)
                .isContentEmpty(true)
                .creatorId(USER_ID)
                .visible(0)
                .build());
        when(userRpcService.findById(USER_ID)).thenReturn(null);

        Response<?> response = noteService.findNoteDetail(
                com.quanxiaoha.xiaohashu.note.biz.model.vo.FindNoteDetailReqVO.builder().id(noteId).build());

        assertTrue(response.isSuccess());
        var detail = (com.quanxiaoha.xiaohashu.note.biz.model.vo.FindNoteDetailRspVO) response.getData();
        assertEquals(noteId, detail.getId());
        assertEquals(null, detail.getCreatorName());
        assertEquals(null, detail.getAvatar());
    }

    @Test
    void makingNotePrivateInvalidatesCachesOnlyAfterTransactionCommit() {
        when(noteDOMapper.selectByPrimaryKey(NOTE_ID)).thenReturn(NoteDO.builder()
                .id(NOTE_ID).creatorId(USER_ID).visible(0).build());
        when(noteDOMapper.updateVisibleOnlyMe(any(NoteDO.class))).thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            noteService.visibleOnlyMe(UpdateNoteVisibleOnlyMeReqVO.builder().id(NOTE_ID).build());
            verify(redisTemplate, never()).delete(any(List.class));
            verify(rocketMQTemplate, never()).syncSend(eq("DeleteNoteLocalCacheTopic"), any(Long.class));
            verify(noteOperateOutboxService, times(2)).enqueueCacheInvalidation(eq(USER_ID), eq(NOTE_ID),
                    anyString(), anyString(), any(LocalDateTime.class));

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            verify(redisTemplate).delete(List.of("note:detail:v2:" + NOTE_ID, "note:published:list:" + USER_ID));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void shouldDeleteDetailAndPublishedListCachesAndScheduleDelayedDeleteWhenUpdating() throws Exception {
        NoteDO existingNote = NoteDO.builder().id(NOTE_ID).creatorId(USER_ID).contentUuid("existing-uuid").build();
        when(noteDOMapper.selectByPrimaryKey(NOTE_ID)).thenReturn(existingNote, existingNote);
        when(noteContentSagaService.prepareClear(eq(NOTE_ID), eq("existing-uuid"), anyString()))
                .thenAnswer(invocation -> NoteContentSagaDO.builder()
                        .contentUuid("existing-uuid")
                        .leaseOwner(invocation.getArgument(2))
                        .leaseVersion(1L)
                        .status(NoteContentSagaService.STATUS_PENDING)
                        .build());

        noteService.updateNote(UpdateNoteReqVO.builder()
                .id(NOTE_ID)
                .type(0)
                .imgUris(List.of("https://cdn.example/updated.jpg"))
                .build());

        verify(redisTemplate).delete(List.of("note:detail:v2:" + NOTE_ID, "note:published:list:" + USER_ID));
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(noteOperateOutboxService, times(2)).enqueueCacheInvalidation(eq(USER_ID), eq(NOTE_ID),
                topicCaptor.capture(), payloadCaptor.capture(), any(LocalDateTime.class));
        assertEquals(List.of("DeleteNoteLocalCacheTopic", "DelayDeleteNoteRedisCacheTopic"), topicCaptor.getAllValues());
        assertEquals(NOTE_ID.toString(), payloadCaptor.getAllValues().get(0));
        assertEquals(List.of(NOTE_ID, USER_ID), JsonUtils.parseList(payloadCaptor.getAllValues().get(1), Long.class));
        verify(noteContentSagaService).markOldContentDeletePending(any(NoteContentSagaDO.class), eq("existing-uuid"));
        verify(keyValueRpcService, never()).deleteNoteContent(anyString());
    }

    @Test
    void changingNoteBodyWritesToANewContentUuidBeforeSwitchingMySqlPointer() {
        String oldUuid = "22222222-2222-4222-8222-222222222222";
        NoteDO existingNote = NoteDO.builder().id(NOTE_ID).creatorId(USER_ID).contentUuid(oldUuid)
                .isContentEmpty(false).build();
        when(noteDOMapper.selectByPrimaryKey(NOTE_ID)).thenReturn(existingNote, existingNote);
        when(keyValueRpcService.saveNoteContent(anyString(), eq("replacement body"))).thenReturn(true);
        when(noteContentSagaService.prepareUpdate(eq(NOTE_ID), anyString(), anyString())).thenAnswer(invocation ->
                NoteContentSagaDO.builder()
                        .leaseOwner(invocation.getArgument(2))
                        .leaseVersion(1L)
                        .contentUuid(invocation.getArgument(1))
                        .status(NoteContentSagaService.STATUS_PENDING)
                        .build());

        noteService.updateNote(UpdateNoteReqVO.builder()
                .id(NOTE_ID)
                .type(0)
                .imgUris(List.of("https://cdn.example/updated.jpg"))
                .content("replacement body")
                .build());

        ArgumentCaptor<String> contentUuidCaptor = ArgumentCaptor.forClass(String.class);
        verify(keyValueRpcService).saveNoteContent(contentUuidCaptor.capture(), eq("replacement body"));
        assertNotNull(UUID.fromString(contentUuidCaptor.getValue()));
        org.junit.jupiter.api.Assertions.assertNotEquals(oldUuid, contentUuidCaptor.getValue());
        ArgumentCaptor<NoteDO> noteCaptor = ArgumentCaptor.forClass(NoteDO.class);
        verify(noteDOMapper).updateContentWithExpectedUuid(noteCaptor.capture(), eq(oldUuid));
        assertEquals(contentUuidCaptor.getValue(), noteCaptor.getValue().getContentUuid());
        var order = org.mockito.Mockito.inOrder(noteContentSagaService, keyValueRpcService, noteDOMapper);
        order.verify(noteContentSagaService).prepareUpdate(eq(NOTE_ID), eq(contentUuidCaptor.getValue()), anyString());
        order.verify(keyValueRpcService).saveNoteContent(eq(contentUuidCaptor.getValue()), eq("replacement body"));
        order.verify(noteDOMapper).updateContentWithExpectedUuid(any(NoteDO.class), eq(oldUuid));
        verify(noteContentSagaService).markOldContentDeletePending(any(NoteContentSagaDO.class), eq(oldUuid));
    }

    @Test
    void concurrentBodyEditCompensatesItsUnreferencedCassandraWrite() {
        String oldUuid = "33333333-3333-4333-8333-333333333333";
        NoteDO existingNote = NoteDO.builder().id(NOTE_ID).creatorId(USER_ID).contentUuid(oldUuid)
                .isContentEmpty(false).build();
        when(noteDOMapper.selectByPrimaryKey(NOTE_ID)).thenReturn(existingNote, existingNote);
        when(keyValueRpcService.saveNoteContent(anyString(), eq("replacement body"))).thenReturn(true);
        when(noteDOMapper.updateContentWithExpectedUuid(any(NoteDO.class), eq(oldUuid))).thenReturn(0);
        when(noteContentSagaService.prepareUpdate(eq(NOTE_ID), anyString(), anyString())).thenAnswer(invocation ->
                NoteContentSagaDO.builder()
                        .leaseOwner(invocation.getArgument(2))
                        .leaseVersion(1L)
                        .contentUuid(invocation.getArgument(1))
                        .status(NoteContentSagaService.STATUS_PENDING)
                        .build());

        BizException exception = assertThrows(BizException.class, () -> noteService.updateNote(UpdateNoteReqVO.builder()
                .id(NOTE_ID)
                .type(0)
                .imgUris(List.of("https://cdn.example/updated.jpg"))
                .content("replacement body")
                .build()));

        assertEquals(ResponseCodeEnum.NOTE_UPDATE_FAIL.getErrorCode(), exception.getErrorCode());
        ArgumentCaptor<String> targetUuid = ArgumentCaptor.forClass(String.class);
        verify(keyValueRpcService).saveNoteContent(targetUuid.capture(), eq("replacement body"));
        verify(noteContentSagaService).requeueLateWriteCleanup(any(NoteContentSagaDO.class), eq(targetUuid.getValue()),
                any(IllegalStateException.class));
        verify(noteContentSagaService, never()).markOldContentDeletePending(any(), anyString());
    }

    @Test
    void requeuesAmbiguousCassandraFailureEvenWhenOriginalSagaLeaseWasTakenOver() {
        String oldUuid = "33333333-3333-4333-8333-333333333334";
        NoteDO existingNote = NoteDO.builder().id(NOTE_ID).creatorId(USER_ID).contentUuid(oldUuid)
                .isContentEmpty(false).build();
        when(noteDOMapper.selectByPrimaryKey(NOTE_ID)).thenReturn(existingNote, existingNote);
        when(noteContentSagaService.prepareUpdate(eq(NOTE_ID), anyString(), anyString())).thenAnswer(invocation ->
                NoteContentSagaDO.builder()
                        .leaseOwner(invocation.getArgument(2))
                        .leaseVersion(1L)
                        .contentUuid(invocation.getArgument(1))
                        .status(NoteContentSagaService.STATUS_PENDING)
                        .build());
        when(keyValueRpcService.saveNoteContent(anyString(), eq("replacement body")))
                .thenThrow(new IllegalStateException("remote write timed out after applying"));

        BizException exception = assertThrows(BizException.class, () -> noteService.updateNote(UpdateNoteReqVO.builder()
                .id(NOTE_ID)
                .type(0)
                .imgUris(List.of("https://cdn.example/updated.jpg"))
                .content("replacement body")
                .build()));

        assertEquals(ResponseCodeEnum.NOTE_UPDATE_FAIL.getErrorCode(), exception.getErrorCode());
        ArgumentCaptor<String> targetUuid = ArgumentCaptor.forClass(String.class);
        verify(keyValueRpcService).saveNoteContent(targetUuid.capture(), eq("replacement body"));
        verify(noteContentSagaService).requeueLateWriteCleanup(any(NoteContentSagaDO.class),
                eq(targetUuid.getValue()), any(IllegalStateException.class));
        verify(noteContentSagaService, never()).markCompensationPending(any(NoteContentSagaDO.class), anyString(),
                any(Throwable.class));
        verify(noteDOMapper, never()).updateContentWithExpectedUuid(any(NoteDO.class), any());
    }

    @Test
    void requeuesLateCassandraWriteWhenItsSagaLeaseExpiredBeforePointerCommit() {
        String oldUuid = "44444444-4444-4444-8444-444444444444";
        NoteDO existingNote = NoteDO.builder().id(NOTE_ID).creatorId(USER_ID).contentUuid(oldUuid)
                .isContentEmpty(false).build();
        when(noteDOMapper.selectByPrimaryKey(NOTE_ID)).thenReturn(existingNote, existingNote);
        when(keyValueRpcService.saveNoteContent(anyString(), eq("replacement body"))).thenReturn(true);
        when(noteContentSagaService.prepareUpdate(eq(NOTE_ID), anyString(), anyString())).thenAnswer(invocation ->
                NoteContentSagaDO.builder()
                        .leaseOwner(invocation.getArgument(2))
                        .leaseVersion(1L)
                        .contentUuid(invocation.getArgument(1))
                        .status(NoteContentSagaService.STATUS_PENDING)
                        .build());
        when(noteContentSagaService.lockLeaseForCommit(any())).thenReturn(false);

        BizException exception = assertThrows(BizException.class, () -> noteService.updateNote(UpdateNoteReqVO.builder()
                .id(NOTE_ID)
                .type(0)
                .imgUris(List.of("https://cdn.example/updated.jpg"))
                .content("replacement body")
                .build()));

        assertEquals(ResponseCodeEnum.NOTE_UPDATE_FAIL.getErrorCode(), exception.getErrorCode());
        ArgumentCaptor<String> targetUuid = ArgumentCaptor.forClass(String.class);
        verify(keyValueRpcService).saveNoteContent(targetUuid.capture(), eq("replacement body"));
        verify(noteContentSagaService).requeueLateWriteCleanup(any(NoteContentSagaDO.class),
                eq(targetUuid.getValue()), any(Throwable.class));
        verify(noteDOMapper, never()).updateContentWithExpectedUuid(any(NoteDO.class), any());
        verify(noteContentSagaService, never()).markOldContentDeletePending(any(), anyString());
    }

    @Test
    void shouldDeleteDetailAndPublishedListCachesAndScheduleDelayedListDeleteWhenDeleting() throws Exception {
        when(noteDOMapper.selectByPrimaryKey(NOTE_ID))
                .thenReturn(NoteDO.builder().id(NOTE_ID).creatorId(USER_ID).build());

        noteService.deleteNote(DeleteNoteReqVO.builder().id(NOTE_ID).build());

        verify(redisTemplate).delete(List.of("note:detail:v2:" + NOTE_ID, "note:published:list:" + USER_ID));
        verify(noteOperateOutboxService, times(2)).enqueueCacheInvalidation(eq(USER_ID), eq(NOTE_ID),
                anyString(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void shouldRetryCacheInvalidationWhenNoteWasAlreadyDeleted() {
        when(noteDOMapper.selectByPrimaryKey(NOTE_ID))
                .thenReturn(NoteDO.builder().id(NOTE_ID).creatorId(USER_ID).build());
        when(noteOperatePersistenceService.softDeleteNote(NOTE_ID, USER_ID)).thenReturn(false);

        noteService.deleteNote(DeleteNoteReqVO.builder().id(NOTE_ID).build());

        verify(redisTemplate).delete(List.of("note:detail:v2:" + NOTE_ID, "note:published:list:" + USER_ID));
        verify(noteOperateOutboxService, times(2)).enqueueCacheInvalidation(eq(USER_ID), eq(NOTE_ID),
                anyString(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void shouldReturnNullDataAndSkipUserLookupWhenPublishedPageIsEmpty() {
        when(noteDOMapper.selectPublishedNoteListByUserIdAndCursor(USER_ID, NOTE_ID)).thenReturn(List.of());

        Response<?> response = noteService.findPublishedNoteList(
                FindPublishedNoteListReqVO.builder().userId(USER_ID).cursor(NOTE_ID).build());

        assertTrue(response.isSuccess());
        assertEquals(null, response.getData());
        verify(noteDOMapper).selectPublishedNoteListByUserIdAndCursor(USER_ID, NOTE_ID);
        verify(userRpcService, never()).findById(anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSendOrderlyUnlikeMessageAfterSuccessfulUnlike() {
        when(noteIdSetCache.contains(anyString(), anyString(), anyLong())).thenReturn(1L);

        SendResult sendResult = mock(SendResult.class);
        when(sendResult.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSendOrderly(anyString(), any(Message.class), anyString())).thenReturn(sendResult);

        noteService.unlikeNote(UnlikeNoteReqVO.builder().id(NOTE_ID).build());

        verify(rocketMQTemplate).syncSendOrderly(eq("LikeUnlikeTopic:Unlike"), any(Message.class),
                eq(USER_ID.toString()));
    }
}
