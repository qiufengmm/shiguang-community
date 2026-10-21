package com.quanxiaoha.xiaohashu.comment.biz.service.impl;

import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentDO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.FindChildCommentItemRspVO;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.FindChildCommentPageListReqVO;
import com.quanxiaoha.xiaohashu.comment.biz.rpc.KeyValueRpcService;
import com.quanxiaoha.xiaohashu.comment.biz.rpc.UserRpcService;
import com.quanxiaoha.framework.common.util.JsonUtils;
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
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentChildPageListServiceTest {

    @Mock
    private CommentDOMapper commentDOMapper;

    @Mock
    private KeyValueRpcService keyValueRpcService;

    @Mock
    private UserRpcService userRpcService;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private ZSetOperations<String, Object> zSetOperations;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;

    @InjectMocks
    private CommentServiceImpl commentService;

    @Test
    void returnsPagedChildCommentsAndReplyTargetProfile() {
        LocalDateTime createdAt = LocalDateTime.now().minusMinutes(30);
        CommentDO directReply = CommentDO.builder()
                .id(32002L)
                .noteId(8100L)
                .userId(3L)
                .contentUuid("child-content-1")
                .isContentEmpty(false)
                .imageUrl("")
                .createTime(createdAt)
                .likeTotal(1L)
                .parentId(30002L)
                .replyCommentId(30002L)
                .replyUserId(27L)
                .build();
        CommentDO replyToChild = CommentDO.builder()
                .id(32003L)
                .noteId(8100L)
                .userId(100L)
                .contentUuid("child-content-2")
                .isContentEmpty(false)
                .imageUrl("")
                .createTime(createdAt.plusMinutes(1))
                .likeTotal(0L)
                .parentId(30002L)
                .replyCommentId(32002L)
                .replyUserId(3L)
                .build();

        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("count:comment:30002", RedisKeyConstants.FIELD_CHILD_COMMENT_TOTAL)).thenReturn(null);
        when(commentDOMapper.selectChildCommentTotalById(30002L)).thenReturn(4L);
        when(redisTemplate.hasKey("comment:childList:30002")).thenReturn(false);
        when(commentDOMapper.selectChildPageList(30002L, 0L, 6L)).thenReturn(List.of(directReply, replyToChild));
        when(keyValueRpcService.batchFindCommentContent(eq(8100L), anyList())).thenReturn(List.of(
                FindCommentContentRspDTO.builder().contentId("child-content-1").content("first reply").build(),
                FindCommentContentRspDTO.builder().contentId("child-content-2").content("second reply").build()));
        when(userRpcService.findByIds(anyList())).thenReturn(List.of(
                FindUserByIdRspDTO.builder().id(3L).nickName("first-user").avatar("first-avatar").build(),
                FindUserByIdRspDTO.builder().id(100L).nickName("second-user").avatar("second-avatar").build()));

        var response = commentService.findChildCommentPageList(FindChildCommentPageListReqVO.builder()
                .parentCommentId(30002L)
                .pageNo(1)
                .build());

        assertThat(response.getPageNo()).isEqualTo(1);
        assertThat(response.getPageSize()).isEqualTo(6);
        assertThat(response.getTotalCount()).isEqualTo(4);
        assertThat(response.getData()).extracting(FindChildCommentItemRspVO::getCommentId)
                .containsExactly(32002L, 32003L);
        FindChildCommentItemRspVO first = response.getData().get(0);
        assertThat(first.getContent()).isEqualTo("first reply");
        assertThat(first.getNickname()).isEqualTo("first-user");
        assertThat(first.getAvatar()).isEqualTo("first-avatar");
        assertThat(first.getReplyUserId()).isNull();
        FindChildCommentItemRspVO second = response.getData().get(1);
        assertThat(second.getContent()).isEqualTo("second reply");
        assertThat(second.getReplyUserName()).isEqualTo("first-user");
        assertThat(second.getReplyUserId()).isEqualTo(3L);
        verify(commentDOMapper).selectChildPageList(30002L, 0L, 6L);
    }

    @Test
    void keepsRepliesWhenBatchUserLookupOmitsTheReplyTarget() {
        CommentDO reply = CommentDO.builder()
                .id(32004L)
                .noteId(8100L)
                .userId(3L)
                .contentUuid("child-content-3")
                .isContentEmpty(true)
                .createTime(LocalDateTime.now().minusMinutes(2))
                .likeTotal(0L)
                .parentId(30002L)
                .replyCommentId(32003L)
                .replyUserId(27L)
                .build();

        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("count:comment:30002", RedisKeyConstants.FIELD_CHILD_COMMENT_TOTAL)).thenReturn(1L);
        when(redisTemplate.hasKey("comment:childList:30002")).thenReturn(false);
        when(commentDOMapper.selectChildPageList(30002L, 0L, 6L)).thenReturn(List.of(reply));
        when(keyValueRpcService.batchFindCommentContent(eq(8100L), anyList())).thenReturn(List.of());
        when(userRpcService.findByIds(anyList())).thenReturn(List.of(
                FindUserByIdRspDTO.builder().id(3L).nickName("reply-author").build()));

        var response = commentService.findChildCommentPageList(FindChildCommentPageListReqVO.builder()
                .parentCommentId(30002L)
                .pageNo(1)
                .build());

        assertThat(response.getData()).singleElement().satisfies(item -> {
            assertThat(item.getCommentId()).isEqualTo(32004L);
            assertThat(item.getNickname()).isEqualTo("reply-author");
            assertThat(item.getReplyUserId()).isEqualTo(27L);
            assertThat(item.getReplyUserName()).isNull();
        });
    }

    @Test
    void returnsCachedChildCommentsAndRefreshesLikeTotalsFromRedisHashes() {
        FindChildCommentItemRspVO firstCachedComment = FindChildCommentItemRspVO.builder()
                .commentId(32002L)
                .userId(3L)
                .nickname("cached-first-user")
                .content("cached first reply")
                .likeTotal(1L)
                .build();
        FindChildCommentItemRspVO secondCachedComment = FindChildCommentItemRspVO.builder()
                .commentId(32003L)
                .userId(100L)
                .nickname("cached-second-user")
                .content("cached second reply")
                .likeTotal(0L)
                .build();
        Set<Object> childCommentIds = new LinkedHashSet<>(List.of(32002L, 32003L));

        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("count:comment:30002", RedisKeyConstants.FIELD_CHILD_COMMENT_TOTAL))
                .thenReturn(4L);
        when(redisTemplate.hasKey("comment:childList:30002")).thenReturn(true);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.range("comment:childList:30002", 0L, 5L))
                .thenReturn(childCommentIds);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(List.of(RedisKeyConstants.buildCommentDetailKey(32002L),
                RedisKeyConstants.buildCommentDetailKey(32003L))))
                .thenReturn(List.of(JsonUtils.toJsonString(firstCachedComment), JsonUtils.toJsonString(secondCachedComment)));
        when(redisTemplate.executePipelined(any(SessionCallback.class)))
                .thenReturn(List.of(
                        Map.of(RedisKeyConstants.FIELD_LIKE_TOTAL, 31L),
                        Map.of(RedisKeyConstants.FIELD_LIKE_TOTAL, 12L)));

        var response = commentService.findChildCommentPageList(FindChildCommentPageListReqVO.builder()
                .parentCommentId(30002L)
                .pageNo(1)
                .build());

        assertThat(response.getTotalCount()).isEqualTo(4);
        assertThat(response.getData()).extracting(FindChildCommentItemRspVO::getCommentId)
                .containsExactly(32002L, 32003L);
        assertThat(response.getData()).extracting(FindChildCommentItemRspVO::getLikeTotal)
                .containsExactly(31L, 12L);
        verify(commentDOMapper, never()).selectChildPageList(30002L, 0L, 6L);
        verify(commentDOMapper, never()).selectChildCommentTotalById(30002L);
    }
}
