package com.quanxiaoha.xiaohashu.comment.biz.service.impl;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.NoteCountDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.FindCommentItemRspVO;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.FindCommentPageListReqVO;
import com.quanxiaoha.xiaohashu.comment.biz.rpc.KeyValueRpcService;
import com.quanxiaoha.xiaohashu.comment.biz.rpc.UserRpcService;
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
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentRedisCacheServiceTest {

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
    void returnsZeroWhenTheCachedCommentTotalIsZero() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("count:note:600", "commentTotal")).thenReturn(0L);

        var response = commentService.findCommentPageList(request());

        assertThat(response.getTotalCount()).isZero();
        assertThat(response.getData()).isNull();
        verify(noteCountDOMapper, never()).selectCommentTotalByNoteId(600L);
        verify(commentDOMapper, never()).selectPageList(600L, 0L, 10L);
    }

    @Test
    void throwsCommentNotFoundWhenNeitherCacheNorCountRowExists() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("count:note:600", "commentTotal")).thenReturn(null);
        when(noteCountDOMapper.selectCommentTotalByNoteId(600L)).thenReturn(null);

        assertThatThrownBy(() -> commentService.findCommentPageList(request()))
                .isInstanceOf(com.quanxiaoha.framework.common.exception.BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", "COMMENT-20001");
    }

    @Test
    void returnsCachedHotCommentDetailsForTheFirstPages() {
        FindCommentItemRspVO cachedComment = FindCommentItemRspVO.builder()
                .commentId(801L)
                .userId(3L)
                .nickname("cached-user")
                .content("cached-body")
                .heat(9.5)
                .build();
        Set<Object> commentIds = new LinkedHashSet<>();
        commentIds.add(801L);

        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("count:note:600", "commentTotal")).thenReturn(1L);
        when(stringRedisTemplate.execute(org.mockito.ArgumentMatchers.any(RedisScript.class),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(RedisKeyConstants.buildHotCommentMember(801L)));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(List.of("comment:detail:v2:801")))
                .thenReturn(List.of(JsonUtils.toJsonString(cachedComment)));
        lenient().when(redisTemplate.executePipelined(org.mockito.ArgumentMatchers.any(SessionCallback.class)))
                .thenReturn(List.of(Map.of()));

        var response = commentService.findCommentPageList(request());

        assertThat(response.getTotalCount()).isEqualTo(1);
        assertThat(response.getData()).hasSize(1);
        assertThat(response.getData().get(0).getCommentId()).isEqualTo(801L);
        assertThat(response.getData().get(0).getNickname()).isEqualTo("cached-user");
        assertThat(response.getData().get(0).getContent()).isEqualTo("cached-body");
        assertThat(response.getData().get(0).getHeat()).isEqualTo(9.5);
        verify(commentDOMapper, never()).selectPageList(600L, 0L, 10L);
    }

    private static FindCommentPageListReqVO request() {
        return FindCommentPageListReqVO.builder()
                .noteId(600L)
                .pageNo(1)
                .build();
    }
}
