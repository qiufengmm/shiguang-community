package com.quanxiaoha.xiaohashu.comment.biz.service.impl;

import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.comment.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.comment.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentLikeDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.model.dto.LikeUnlikeCommentMqDTO;
import com.quanxiaoha.xiaohashu.comment.biz.model.dto.PublishCommentMqDTO;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.LikeCommentReqVO;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.UnLikeCommentReqVO;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.PublishCommentReqVO;
import com.quanxiaoha.xiaohashu.comment.biz.retry.SendMqRetryHelper;
import com.quanxiaoha.xiaohashu.comment.biz.rpc.DistributedIdGeneratorRpcService;
import com.quanxiaoha.xiaohashu.comment.biz.service.CommentOutboxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CommentServiceImplTest {

    private SendMqRetryHelper sendMqRetryHelper;
    private CommentOutboxService commentOutboxService;
    private TransactionTemplate transactionTemplate;
    private DistributedIdGeneratorRpcService distributedIdGeneratorRpcService;
    private CommentDOMapper commentDOMapper;
    private CommentLikeDOMapper commentLikeDOMapper;
    private RedisTemplate<String, Object> redisTemplate;
    private RocketMQTemplate rocketMQTemplate;
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;
    private CommentServiceImpl commentService;
    private long commentId;
    private long creatorId;
    private long noteId;
    private long replyCommentId;

    @BeforeEach
    void setUp() {
        sendMqRetryHelper = mock(SendMqRetryHelper.class);
        commentOutboxService = mock(CommentOutboxService.class);
        transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        distributedIdGeneratorRpcService = mock(DistributedIdGeneratorRpcService.class);
        commentDOMapper = mock(CommentDOMapper.class);
        commentLikeDOMapper = mock(CommentLikeDOMapper.class);
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redisTemplateMock = mock(RedisTemplate.class);
        redisTemplate = redisTemplateMock;
        rocketMQTemplate = mock(RocketMQTemplate.class);
        threadPoolTaskExecutor = mock(ThreadPoolTaskExecutor.class);
        commentService = new CommentServiceImpl();
        ReflectionTestUtils.setField(commentService, "sendMqRetryHelper", sendMqRetryHelper);
        ReflectionTestUtils.setField(commentService, "commentOutboxService", commentOutboxService);
        ReflectionTestUtils.setField(commentService, "transactionTemplate", transactionTemplate);
        ReflectionTestUtils.setField(commentService, "distributedIdGeneratorRpcService", distributedIdGeneratorRpcService);
        ReflectionTestUtils.setField(commentService, "commentDOMapper", commentDOMapper);
        ReflectionTestUtils.setField(commentService, "commentLikeDOMapper", commentLikeDOMapper);
        ReflectionTestUtils.setField(commentService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(commentService, "rocketMQTemplate", rocketMQTemplate);
        ReflectionTestUtils.setField(commentService, "threadPoolTaskExecutor", threadPoolTaskExecutor);
        commentId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
        when(distributedIdGeneratorRpcService.generateCommentId()).thenReturn(String.valueOf(commentId));
        creatorId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
        noteId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
        replyCommentId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
        LoginUserContextHolder.setUserId(creatorId);
    }

    @AfterEach
    void cleanUp() {
        LoginUserContextHolder.remove();
    }

    @Test
    void rejectsCommentWhenTextAndImageAreBothBlank() {
        PublishCommentReqVO request = PublishCommentReqVO.builder()
                .noteId(noteId)
                .content("  ")
                .imageUrl("")
                .build();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> commentService.publishComment(request))
                .withMessage("评论正文和图片不能同时为空");
        verifyNoInteractions(sendMqRetryHelper, distributedIdGeneratorRpcService);
    }

    @Test
    void durablyAcceptsPublishWithStableCommentAndContentIds() {
        PublishCommentReqVO request = PublishCommentReqVO.builder()
                .noteId(noteId)
                .content("integration test comment")
                .imageUrl("https://example.invalid/comment-test.png")
                .replyCommentId(replyCommentId)
                .build();

        commentService.publishComment(request);

        org.mockito.ArgumentCaptor<String> payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(commentOutboxService).enqueue(eq("comment-publish:" + commentId),
                eq(MQConstants.TOPIC_PUBLISH_COMMENT), payloadCaptor.capture());
        PublishCommentMqDTO message = JsonUtils.parseObject(payloadCaptor.getValue(), PublishCommentMqDTO.class);

        assertThat(message.getNoteId()).isEqualTo(noteId);
        assertThat(message.getCommentId()).isEqualTo(commentId);
        assertThat(message.getContentUuid()).isNotBlank();
        assertThat(message.getCreatorId()).isEqualTo(creatorId);
        assertThat(message.getReplyCommentId()).isEqualTo(replyCommentId);
        assertThat(message.getCreateTime()).isNotNull();
        verify(sendMqRetryHelper, never()).asyncSend(any(), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void skipsOverlappingHotCommentRebuildWhenAnotherInstanceOwnsTheRedisLock() {
        StringRedisTemplate lockTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> lockValueOperations = mock(ValueOperations.class);
        when(lockTemplate.opsForValue()).thenReturn(lockValueOperations);
        AtomicInteger lockAttempts = new AtomicInteger();
        when(lockValueOperations.setIfAbsent(any(), any(), any(Duration.class)))
                .thenAnswer(invocation -> lockAttempts.incrementAndGet() == 1);
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(any())).thenReturn("0");
        ReflectionTestUtils.setField(commentService, "stringRedisTemplate", lockTemplate);

        ReflectionTestUtils.invokeMethod(commentService, "syncHeatComments2Redis",
                RedisKeyConstants.buildCommentListKey(noteId), noteId);
        ReflectionTestUtils.invokeMethod(commentService, "syncHeatComments2Redis",
                RedisKeyConstants.buildCommentListKey(noteId), noteId);

        verify(lockValueOperations, org.mockito.Mockito.times(2))
                .setIfAbsent(any(), any(), any(Duration.class));
        verify(commentDOMapper, org.mockito.Mockito.times(1)).selectHeatComments(noteId);
    }

    @Test
    void sendsOrderedLikeMessageWhenBloomScriptAddsComment() {
        String detailKey = RedisKeyConstants.buildCommentDetailKey(commentId);
        String bloomKey = RedisKeyConstants.buildBloomCommentLikesKey(creatorId);
        when(redisTemplate.hasKey(detailKey)).thenReturn(true);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq(commentId))).thenReturn(0L);

        commentService.likeComment(LikeCommentReqVO.builder().commentId(commentId).build());

        org.mockito.ArgumentCaptor<Message<String>> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).asyncSendOrderly(
                eq(MQConstants.TOPIC_COMMENT_LIKE_OR_UNLIKE + ":" + MQConstants.TAG_LIKE),
                messageCaptor.capture(), eq(String.valueOf(creatorId)), any(SendCallback.class));
        LikeUnlikeCommentMqDTO message = JsonUtils.parseObject(messageCaptor.getValue().getPayload(), LikeUnlikeCommentMqDTO.class);
        assertThat(message.getUserId()).isEqualTo(creatorId);
        assertThat(message.getCommentId()).isEqualTo(commentId);
        assertThat(message.getType()).isEqualTo(1);
        assertThat(message.getCreateTime()).isNotNull();
        verify(redisTemplate).execute(any(DefaultRedisScript.class), eq(List.of(bloomKey)), eq(commentId));
        verify(commentDOMapper, never()).selectByPrimaryKey(commentId);
    }

    @Test
    void rejectsLikeWhenCommentDoesNotExist() {
        String detailKey = RedisKeyConstants.buildCommentDetailKey(commentId);
        when(redisTemplate.hasKey(detailKey)).thenReturn(false);
        when(commentDOMapper.selectByPrimaryKey(commentId)).thenReturn(null);

        assertThatThrownBy(() -> commentService.likeComment(LikeCommentReqVO.builder().commentId(commentId).build()))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", "COMMENT-20001");

        verify(redisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), any());
        verifyNoInteractions(rocketMQTemplate);
    }

    @Test
    void rejectsDuplicateLikeWhenBloomFilterIsMissingAndDatabaseContainsLike() {
        String detailKey = RedisKeyConstants.buildCommentDetailKey(commentId);
        String bloomKey = RedisKeyConstants.buildBloomCommentLikesKey(creatorId);
        when(redisTemplate.hasKey(detailKey)).thenReturn(true);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq(commentId))).thenReturn(-1L);
        when(commentLikeDOMapper.selectCountByUserIdAndCommentId(creatorId, commentId)).thenReturn(1);

        assertThatThrownBy(() -> commentService.likeComment(LikeCommentReqVO.builder().commentId(commentId).build()))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", "COMMENT-20002");

        verify(commentLikeDOMapper).selectCountByUserIdAndCommentId(creatorId, commentId);
        verify(threadPoolTaskExecutor).submit(any(Runnable.class));
        verify(redisTemplate).execute(any(DefaultRedisScript.class), eq(List.of(bloomKey)), eq(commentId));
        verifyNoInteractions(rocketMQTemplate);
    }

    @Test
    void checksDatabaseBeforeTreatingBloomPositiveAsDuplicate() {
        String detailKey = RedisKeyConstants.buildCommentDetailKey(commentId);
        when(redisTemplate.hasKey(detailKey)).thenReturn(true);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq(commentId))).thenReturn(1L);
        when(commentLikeDOMapper.selectCountByUserIdAndCommentId(creatorId, commentId)).thenReturn(0);

        commentService.likeComment(LikeCommentReqVO.builder().commentId(commentId).build());

        verify(commentLikeDOMapper).selectCountByUserIdAndCommentId(creatorId, commentId);
        verify(rocketMQTemplate).asyncSendOrderly(
                eq(MQConstants.TOPIC_COMMENT_LIKE_OR_UNLIKE + ":" + MQConstants.TAG_LIKE),
                any(Message.class), eq(String.valueOf(creatorId)), any(SendCallback.class));
    }

    @Test
    void rebuildsMissingBloomFilterAndAddsCurrentCommentBeforeSendingLikeMessage() {
        String detailKey = RedisKeyConstants.buildCommentDetailKey(commentId);
        String bloomKey = RedisKeyConstants.buildBloomCommentLikesKey(creatorId);
        Long existingLikedCommentId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
        when(redisTemplate.hasKey(detailKey)).thenReturn(true);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq(commentId)))
                .thenReturn(-1L, 0L);
        when(commentLikeDOMapper.selectCountByUserIdAndCommentId(creatorId, commentId)).thenReturn(0);
        when(commentLikeDOMapper.selectByUserId(creatorId)).thenReturn(List.of(
                com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentLikeDO.builder()
                        .commentId(existingLikedCommentId)
                        .build()));

        commentService.likeComment(LikeCommentReqVO.builder().commentId(commentId).build());

        verify(commentLikeDOMapper).selectByUserId(creatorId);
        verify(redisTemplate).execute(any(DefaultRedisScript.class), eq(List.of(bloomKey)),
                eq(existingLikedCommentId), any());
        verify(redisTemplate).execute(any(DefaultRedisScript.class), eq(List.of(bloomKey)),
                eq(commentId), any());
        verify(rocketMQTemplate).asyncSendOrderly(
                eq(MQConstants.TOPIC_COMMENT_LIKE_OR_UNLIKE + ":" + MQConstants.TAG_LIKE),
                any(Message.class), eq(String.valueOf(creatorId)), any(SendCallback.class));
    }

    @Test
    void rejectsUnlikeWhenBloomFilterConfirmsCommentWasNotLiked() {
        String detailKey = RedisKeyConstants.buildCommentDetailKey(commentId);
        when(redisTemplate.hasKey(detailKey)).thenReturn(true);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq(commentId))).thenReturn(0L);

        assertThatThrownBy(() -> commentService.unlikeComment(
                UnLikeCommentReqVO.builder().commentId(commentId).build()))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", "COMMENT-20003");

        verifyNoInteractions(rocketMQTemplate);
        verify(commentLikeDOMapper, never()).selectCountByUserIdAndCommentId(creatorId, commentId);
    }

    @Test
    void sendsOrderedUnlikeMessageWhenBloomFilterConfirmsCommentWasLiked() {
        String detailKey = RedisKeyConstants.buildCommentDetailKey(commentId);
        when(redisTemplate.hasKey(detailKey)).thenReturn(true);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq(commentId))).thenReturn(1L);

        commentService.unlikeComment(UnLikeCommentReqVO.builder().commentId(commentId).build());

        org.mockito.ArgumentCaptor<Message<String>> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).asyncSendOrderly(
                eq(MQConstants.TOPIC_COMMENT_LIKE_OR_UNLIKE + ":UnLike"),
                messageCaptor.capture(), eq(String.valueOf(creatorId)), any(SendCallback.class));
        LikeUnlikeCommentMqDTO message = JsonUtils.parseObject(
                messageCaptor.getValue().getPayload(), LikeUnlikeCommentMqDTO.class);
        assertThat(message.getUserId()).isEqualTo(creatorId);
        assertThat(message.getCommentId()).isEqualTo(commentId);
        assertThat(message.getType()).isEqualTo(0);
        assertThat(message.getCreateTime()).isNotNull();
        verify(commentLikeDOMapper, never()).selectCountByUserIdAndCommentId(creatorId, commentId);
    }

    @Test
    void rejectsUnlikeWhenBloomFilterIsMissingAndDatabaseHasNoLike() {
        String detailKey = RedisKeyConstants.buildCommentDetailKey(commentId);
        String bloomKey = RedisKeyConstants.buildBloomCommentLikesKey(creatorId);
        when(redisTemplate.hasKey(detailKey)).thenReturn(true);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq(commentId))).thenReturn(-1L);
        when(commentLikeDOMapper.selectCountByUserIdAndCommentId(creatorId, commentId)).thenReturn(0);

        assertThatThrownBy(() -> commentService.unlikeComment(
                UnLikeCommentReqVO.builder().commentId(commentId).build()))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", "COMMENT-20003");

        verify(commentLikeDOMapper).selectCountByUserIdAndCommentId(creatorId, commentId);
        verify(threadPoolTaskExecutor).submit(any(Runnable.class));
        verify(redisTemplate).execute(any(DefaultRedisScript.class), eq(List.of(bloomKey)), eq(commentId));
        verifyNoInteractions(rocketMQTemplate);
    }

    @Test
    void sendsOrderedUnlikeWhenBloomFilterIsMissingButDatabaseHasLike() {
        String detailKey = RedisKeyConstants.buildCommentDetailKey(commentId);
        when(redisTemplate.hasKey(detailKey)).thenReturn(true);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq(commentId))).thenReturn(-1L);
        when(commentLikeDOMapper.selectCountByUserIdAndCommentId(creatorId, commentId)).thenReturn(1);

        commentService.unlikeComment(UnLikeCommentReqVO.builder().commentId(commentId).build());

        verify(threadPoolTaskExecutor).submit(any(Runnable.class));
        verify(rocketMQTemplate).asyncSendOrderly(
                eq(MQConstants.TOPIC_COMMENT_LIKE_OR_UNLIKE + ":UnLike"),
                any(Message.class), eq(String.valueOf(creatorId)), any(SendCallback.class));
    }
}
