package com.quanxiaoha.xiaohashu.comment.biz.service.impl;

import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.NoteCountDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentDO;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.comment.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.FindCommentItemRspVO;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.FindCommentPageListReqVO;
import com.quanxiaoha.xiaohashu.comment.biz.rpc.KeyValueRpcService;
import com.quanxiaoha.xiaohashu.comment.biz.rpc.UserRpcService;
import com.quanxiaoha.xiaohashu.kv.dto.rsp.FindCommentContentRspDTO;
import com.quanxiaoha.xiaohashu.user.dto.resp.FindUserByIdRspDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentPageListServiceTest {

    @Mock
    private CommentDOMapper commentDOMapper;

    @Mock
    private NoteCountDOMapper noteCountDOMapper;

    @Mock
    private KeyValueRpcService keyValueRpcService;

    @Mock
    private UserRpcService userRpcService;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;

    @InjectMocks
    private CommentServiceImpl commentService;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void readsZeroPaddedHotCommentIdsWithStringSerialization() {
        long noteId = 601L;
        long commentId = 6001L;
        String member = RedisKeyConstants.buildHotCommentMember(commentId);
        FindCommentItemRspVO cachedDetail = FindCommentItemRspVO.builder()
                .commentId(commentId)
                .content("cached comment body")
                .build();

        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(RedisKeyConstants.buildNoteCommentTotalKey(noteId),
                RedisKeyConstants.FIELD_COMMENT_TOTAL)).thenReturn(1L);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.multiGet(List.of(RedisKeyConstants.buildCommentDetailKey(commentId))))
                .thenReturn(List.of(JsonUtils.toJsonString(cachedDetail)));
        lenient().when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(Collections.singletonList(null));
        lenient().when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(List.of(member));
        when(redisTemplate.executePipelined(any(SessionCallback.class)))
                .thenReturn(List.of(Map.of(RedisKeyConstants.FIELD_LIKE_TOTAL, 3L,
                        RedisKeyConstants.FIELD_CHILD_COMMENT_TOTAL, 0L)));

        var response = commentService.findCommentPageList(FindCommentPageListReqVO.builder()
                .noteId(noteId)
                .pageNo(1)
                .build());

        assertThat(response.getData()).hasSize(1);
        assertThat(response.getData().get(0).getCommentId()).isEqualTo(commentId);
        assertThat(response.getData().get(0).getContent()).isEqualTo("cached comment body");
        verify(stringRedisTemplate).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void treatsNilHotCommentScriptReplyAsCacheMiss() {
        long noteId = 602L;
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(RedisKeyConstants.buildNoteCommentTotalKey(noteId),
                RedisKeyConstants.FIELD_COMMENT_TOTAL)).thenReturn(1L);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(Collections.singletonList(null));
        when(commentDOMapper.selectPageList(noteId, 0L, 10L)).thenReturn(List.of());

        var response = commentService.findCommentPageList(FindCommentPageListReqVO.builder()
                .noteId(noteId)
                .pageNo(1)
                .build());

        assertThat(response.getTotalCount()).isEqualTo(1);
        assertThat(response.getData()).isEmpty();
        verify(commentDOMapper).selectPageList(noteId, 0L, 10L);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void treatsAnEmptyHotCommentPageAsAReadyCacheHit() {
        long noteId = 604L;
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(RedisKeyConstants.buildNoteCommentTotalKey(noteId),
                RedisKeyConstants.FIELD_COMMENT_TOTAL)).thenReturn(20L);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(Collections.emptyList());

        var response = commentService.findCommentPageList(FindCommentPageListReqVO.builder()
                .noteId(noteId)
                .pageNo(2)
                .build());

        assertThat(response.getTotalCount()).isEqualTo(20);
        assertThat(response.getData()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(commentDOMapper, threadPoolTaskExecutor);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void readsLegacyJsonQuotedHotCommentIds() {
        long noteId = 603L;
        long commentId = 6003L;
        String member = RedisKeyConstants.buildHotCommentMember(commentId);
        FindCommentItemRspVO cachedDetail = FindCommentItemRspVO.builder()
                .commentId(commentId)
                .content("legacy cached comment body")
                .build();

        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(RedisKeyConstants.buildNoteCommentTotalKey(noteId),
                RedisKeyConstants.FIELD_COMMENT_TOTAL)).thenReturn(1L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(List.of(RedisKeyConstants.buildCommentDetailKey(commentId))))
                .thenReturn(List.of(JsonUtils.toJsonString(cachedDetail)));
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(List.of("\"" + member + "\""));
        when(redisTemplate.executePipelined(any(SessionCallback.class)))
                .thenReturn(List.of(Map.of(RedisKeyConstants.FIELD_LIKE_TOTAL, 0L,
                        RedisKeyConstants.FIELD_CHILD_COMMENT_TOTAL, 0L)));

        var response = commentService.findCommentPageList(FindCommentPageListReqVO.builder()
                .noteId(noteId)
                .pageNo(1)
                .build());

        assertThat(response.getData()).singleElement()
                .extracting(FindCommentItemRspVO::getCommentId).isEqualTo(commentId);
    }

    @Test
    void assemblesPagedRootCommentsWithFirstReplyContentAndUserProfiles() {
        LocalDateTime createdAt = LocalDateTime.now().minusHours(2);
        CommentDO root = CommentDO.builder()
                .id(7001L)
                .userId(3L)
                .contentUuid("root-content")
                .isContentEmpty(false)
                .createTime(createdAt)
                .likeTotal(4L)
                .childCommentTotal(2L)
                .firstReplyCommentId(7002L)
                .build();
        CommentDO firstReply = CommentDO.builder()
                .id(7002L)
                .userId(100L)
                .contentUuid("reply-content")
                .isContentEmpty(false)
                .createTime(createdAt.plusMinutes(5))
                .likeTotal(1L)
                .build();

        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("count:note:600", "commentTotal")).thenReturn(null);
        when(noteCountDOMapper.selectCommentTotalByNoteId(600L)).thenReturn(12L);
        when(commentDOMapper.selectPageList(600L, 10L, 10L)).thenReturn(List.of(root));
        when(commentDOMapper.selectTwoLevelCommentByIds(List.of(7002L))).thenReturn(List.of(firstReply));
        when(keyValueRpcService.batchFindCommentContent(eq(600L), anyList())).thenReturn(List.of(
                FindCommentContentRspDTO.builder().contentId("root-content").content("Root body").build(),
                FindCommentContentRspDTO.builder().contentId("reply-content").content("Reply body").build()));
        when(userRpcService.findByIds(List.of(3L, 100L))).thenReturn(List.of(
                FindUserByIdRspDTO.builder().id(3L).nickName("root-user").avatar("root-avatar").build(),
                FindUserByIdRspDTO.builder().id(100L).nickName("reply-user").avatar("reply-avatar").build()));

        var response = commentService.findCommentPageList(FindCommentPageListReqVO.builder()
                .noteId(600L)
                .pageNo(2)
                .build());

        assertThat(response.getPageNo()).isEqualTo(2);
        assertThat(response.getPageSize()).isEqualTo(10);
        assertThat(response.getTotalCount()).isEqualTo(12);
        assertThat(response.getData()).hasSize(1);
        FindCommentItemRspVO rootResult = response.getData().get(0);
        assertThat(rootResult.getCommentId()).isEqualTo(7001L);
        assertThat(rootResult.getUserId()).isEqualTo(3L);
        assertThat(rootResult.getNickname()).isEqualTo("root-user");
        assertThat(rootResult.getAvatar()).isEqualTo("root-avatar");
        assertThat(rootResult.getContent()).isEqualTo("Root body");
        assertThat(rootResult.getLikeTotal()).isEqualTo(4L);
        assertThat(rootResult.getChildCommentTotal()).isEqualTo(2L);
        assertThat(rootResult.getFirstReplyComment()).satisfies(reply -> {
            assertThat(reply.getCommentId()).isEqualTo(7002L);
            assertThat(reply.getUserId()).isEqualTo(100L);
            assertThat(reply.getNickname()).isEqualTo("reply-user");
            assertThat(reply.getAvatar()).isEqualTo("reply-avatar");
            assertThat(reply.getContent()).isEqualTo("Reply body");
        });
        verify(commentDOMapper).selectPageList(600L, 10L, 10L);
    }
}
