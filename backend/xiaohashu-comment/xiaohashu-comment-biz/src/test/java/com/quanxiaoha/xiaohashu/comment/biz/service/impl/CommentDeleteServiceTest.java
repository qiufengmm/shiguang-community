package com.quanxiaoha.xiaohashu.comment.biz.service.impl;

import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.xiaohashu.comment.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.comment.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentDO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentTombstoneMapper;
import com.quanxiaoha.xiaohashu.comment.biz.service.CommentOutboxService;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.DeleteCommentReqVO;
import com.quanxiaoha.xiaohashu.comment.biz.rpc.KeyValueRpcService;
import com.quanxiaoha.xiaohashu.comment.biz.service.impl.CommentServiceImpl;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CommentDeleteServiceTest {

    private final Long commentId = 7821345601L;
    private final Long noteId = 7345012907L;
    private final Long authorId = 5198372640L;
    private final LocalDateTime createTime = LocalDateTime.of(2026, 10, 17, 12, 30);

    private CommentDOMapper commentDOMapper;
    private CommentTombstoneMapper commentTombstoneMapper;
    private CommentOutboxService commentOutboxService;
    private KeyValueRpcService keyValueRpcService;
    private RedisTemplate<String, Object> redisTemplate;
    private RocketMQTemplate rocketMQTemplate;
    private TransactionTemplate transactionTemplate;
    private CommentServiceImpl commentService;

    @BeforeEach
    void setUp() {
        commentDOMapper = mock(CommentDOMapper.class);
        commentTombstoneMapper = mock(CommentTombstoneMapper.class);
        commentOutboxService = mock(CommentOutboxService.class);
        keyValueRpcService = mock(KeyValueRpcService.class);
        redisTemplate = mock(RedisTemplate.class);
        rocketMQTemplate = mock(RocketMQTemplate.class);
        transactionTemplate = mock(TransactionTemplate.class);
        commentService = new CommentServiceImpl();

        ReflectionTestUtils.setField(commentService, "commentDOMapper", commentDOMapper);
        ReflectionTestUtils.setField(commentService, "commentTombstoneMapper", commentTombstoneMapper);
        ReflectionTestUtils.setField(commentService, "commentOutboxService", commentOutboxService);
        ReflectionTestUtils.setField(commentService, "keyValueRpcService", keyValueRpcService);
        ReflectionTestUtils.setField(commentService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(commentService, "rocketMQTemplate", rocketMQTemplate);
        ReflectionTestUtils.setField(commentService, "transactionTemplate", transactionTemplate);
        LoginUserContextHolder.setUserId(authorId);
    }

    @AfterEach
    void cleanUp() {
        LoginUserContextHolder.remove();
    }

    @Test
    void rejectsDeleteWhenCommentDoesNotExist() {
        when(commentDOMapper.selectByPrimaryKey(commentId)).thenReturn(null);

        assertThatThrownBy(() -> commentService.deleteComment(DeleteCommentReqVO.builder()
                .commentId(commentId)
                .build()))
                .isInstanceOf(BizException.class);

        verifyNoInteractions(keyValueRpcService, redisTemplate, rocketMQTemplate, transactionTemplate);
    }

    @Test
    void rejectsDeleteWhenCurrentUserIsNotCommentAuthor() {
        when(commentDOMapper.selectByPrimaryKey(commentId)).thenReturn(comment(123L));

        assertThatThrownBy(() -> commentService.deleteComment(DeleteCommentReqVO.builder()
                .commentId(commentId)
                .build()))
                .isInstanceOf(BizException.class);

        verify(commentDOMapper, never()).deleteByPrimaryKey(commentId);
        verifyNoInteractions(keyValueRpcService, redisTemplate, rocketMQTemplate, transactionTemplate);
    }

    @Test
    void persistsDeleteTombstoneAndOutboxWithoutCallingKvInsideSqlTransaction() {
        CommentDO comment = comment(authorId);
        when(commentDOMapper.selectByPrimaryKey(commentId)).thenReturn(comment);
        when(commentDOMapper.selectByPrimaryKeyForUpdate(commentId)).thenReturn(comment);
        when(commentDOMapper.selectDeleteTreeByRootCommentId(commentId)).thenReturn(List.of(comment));
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any(TransactionCallback.class));

        @SuppressWarnings("unchecked")
        RedisOperations<String, Object> redisOperations = mock(RedisOperations.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, Object> zSetOperations = mock(ZSetOperations.class);
        when(redisOperations.opsForZSet()).thenReturn(zSetOperations);
        doAnswer(invocation -> {
            SessionCallback<?> callback = invocation.getArgument(0);
            callback.execute(redisOperations);
            return List.of();
        }).when(redisTemplate).executePipelined(any(SessionCallback.class));

        assertThat(commentService.deleteComment(DeleteCommentReqVO.builder()
                .commentId(commentId)
                .build()).isSuccess()).isTrue();

        verify(commentDOMapper).deleteByPrimaryKey(commentId);
        verify(commentTombstoneMapper).insertIfAbsent(org.mockito.ArgumentMatchers.any());
        verify(keyValueRpcService, never()).deleteCommentContent(noteId, createTime, "uuid-test-content");
        verify(commentOutboxService).enqueue(eq("comment-delete:" + commentId), eq(MQConstants.TOPIC_DELETE_COMMENT), any());
        verify(commentOutboxService).enqueue(eq("comment-local-cache-delete:" + commentId),
                eq(MQConstants.TOPIC_DELETE_COMMENT_LOCAL_CACHE), eq(String.valueOf(commentId)));
        verifyNoInteractions(redisTemplate);
        verifyNoInteractions(rocketMQTemplate);
    }

    @Test
    void deletingChildLocksItsLevelOneAncestorBeforeTheTargetRow() {
        Long parentId = 900L;
        CommentDO child = CommentDO.builder().id(commentId).noteId(noteId).userId(authorId)
                .contentUuid("uuid-test-content").level(2).parentId(parentId).createTime(createTime).build();
        CommentDO parent = CommentDO.builder().id(parentId).noteId(noteId).userId(9001L)
                .level(1).createTime(createTime).build();
        when(commentDOMapper.selectByPrimaryKey(commentId)).thenReturn(child);
        when(commentDOMapper.selectByPrimaryKeyForUpdate(parentId)).thenReturn(parent);
        when(commentDOMapper.selectByPrimaryKeyForUpdate(commentId)).thenReturn(child);
        when(commentDOMapper.selectDeleteTreeByRootCommentId(commentId)).thenReturn(List.of(child));
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any(TransactionCallback.class));

        commentService.deleteComment(DeleteCommentReqVO.builder().commentId(commentId).build());

        org.mockito.InOrder order = inOrder(commentDOMapper);
        order.verify(commentDOMapper).selectByPrimaryKey(commentId);
        order.verify(commentDOMapper).selectByPrimaryKeyForUpdate(parentId);
        order.verify(commentDOMapper).selectByPrimaryKeyForUpdate(commentId);
    }

    private CommentDO comment(Long userId) {
        return CommentDO.builder()
                .id(commentId)
                .noteId(noteId)
                .userId(userId)
                .contentUuid("uuid-test-content")
                .level(1)
                .createTime(createTime)
                .build();
    }
}
