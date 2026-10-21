package com.quanxiaoha.xiaohashu.comment.biz.service.impl;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.NoteCountDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.FindCommentItemRspVO;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.FindCommentPageListReqVO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentDO;
import com.quanxiaoha.xiaohashu.kv.dto.rsp.FindCommentContentRspDTO;
import com.quanxiaoha.xiaohashu.user.dto.resp.FindUserByIdRspDTO;
import com.quanxiaoha.xiaohashu.comment.biz.rpc.KeyValueRpcService;
import com.quanxiaoha.xiaohashu.comment.biz.rpc.UserRpcService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class CommentLocalCacheServiceTest {

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

    @BeforeEach
    void clearLocalCache() {
        Object localCache = ReflectionTestUtils.getField(CommentServiceImpl.class, "LOCAL_CACHE");
        ReflectionTestUtils.invokeMethod(localCache, "invalidateAll");
    }

    @AfterEach
    void invalidateLocalCacheAfterTest() {
        Object localCache = ReflectionTestUtils.getField(CommentServiceImpl.class, "LOCAL_CACHE");
        ReflectionTestUtils.invokeMethod(localCache, "invalidateAll");
    }

    @Test
    void returnsLocalCommentDetailsWithoutCallingRedisMgetOrDatabase() {
        putLocalComment(801L, "local-first", "first body", 9.5);
        putLocalComment(802L, "local-second", "second body", 6.2);
        Set<Object> commentIds = orderedIds(801L, 802L);
        stubCachedCountAndHotCommentIds(commentIds, 2L);

        var response = commentService.findCommentPageList(request());

        assertThat(response.getData()).extracting(FindCommentItemRspVO::getCommentId)
                .containsExactly(801L, 802L);
        assertThat(response.getData()).extracting(FindCommentItemRspVO::getContent)
                .containsExactly("first body", "second body");
        verify(redisTemplate, never()).opsForValue();
        verify(commentDOMapper, never()).selectPageList(600L, 0L, 10L);
    }

    @Test
    void loadsCountersFromCommentHashesForLocalCommentDetails() {
        FindCommentItemRspVO firstReply = FindCommentItemRspVO.builder()
                .commentId(802L)
                .likeTotal(0L)
                .build();
        FindCommentItemRspVO root = FindCommentItemRspVO.builder()
                .commentId(801L)
                .nickname("local-root")
                .content("root body")
                .heat(9.5)
                .childCommentTotal(0L)
                .likeTotal(0L)
                .firstReplyComment(firstReply)
                .build();
        Object localCache = ReflectionTestUtils.getField(CommentServiceImpl.class, "LOCAL_CACHE");
        ReflectionTestUtils.invokeMethod(localCache, "put", 801L, JsonUtils.toJsonString(root));
        Set<Object> commentIds = orderedIds(801L);
        stubCachedCountAndHotCommentIds(commentIds, 1L);
        HashOperations<String, Object, Object> pipelineHashOperations = mock(HashOperations.class);
        RedisOperations<String, Object> redisOperations = mock(RedisOperations.class);
        Map<Object, Object> rootCounts = Map.of("childCommentTotal", 5L, "likeTotal", 7L);
        Map<Object, Object> replyCounts = Map.of("likeTotal", 3L);
        when(redisOperations.opsForHash()).thenReturn(pipelineHashOperations);
        when(pipelineHashOperations.entries("count:comment:801")).thenReturn(rootCounts);
        when(pipelineHashOperations.entries("count:comment:802")).thenReturn(replyCounts);
        when(redisTemplate.executePipelined(any(SessionCallback.class))).thenAnswer(invocation -> {
            SessionCallback<?> callback = invocation.getArgument(0);
            callback.execute(redisOperations);
            return List.of(rootCounts, replyCounts);
        });

        var response = commentService.findCommentPageList(request());

        assertThat(response.getData()).singleElement().satisfies(comment -> {
            assertThat(comment.getChildCommentTotal()).isEqualTo(5L);
            assertThat(comment.getLikeTotal()).isEqualTo(7L);
            assertThat(comment.getFirstReplyComment().getLikeTotal()).isEqualTo(3L);
        });
    }

    @Test
    void loadsOnlyLocalMissesFromRedisAndRestoresHeatOrder() {
        putLocalComment(801L, "local-first", "first body", 9.5);
        Set<Object> commentIds = orderedIds(801L, 802L);
        stubCachedCountAndHotCommentIds(commentIds, 2L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(List.of(RedisKeyConstants.buildCommentDetailKey(802L))))
                .thenReturn(List.of(JsonUtils.toJsonString(FindCommentItemRspVO.builder()
                        .commentId(802L)
                        .nickname("redis-second")
                        .content("second body")
                        .heat(6.2)
                        .build())));

        var response = commentService.findCommentPageList(request());

        assertThat(response.getData()).extracting(FindCommentItemRspVO::getCommentId)
                .containsExactly(801L, 802L);
        assertThat(response.getData()).extracting(FindCommentItemRspVO::getHeat)
                .containsExactly(9.5, 6.2);
        verify(valueOperations).multiGet(List.of(RedisKeyConstants.buildCommentDetailKey(802L)));
        verify(commentDOMapper, never()).selectByCommentIds(List.of(801L, 802L));
    }

    @Test
    void doesNotCacheMissingLocalCommentAsAnEmptyPlaceholder() {
        putLocalComment(801L, "local-first", "first body", 9.5);
        Set<Object> commentIds = orderedIds(801L, 802L);
        stubCachedCountAndHotCommentIds(commentIds, 2L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(List.of(RedisKeyConstants.buildCommentDetailKey(802L)))).thenReturn(List.of(
                JsonUtils.toJsonString(FindCommentItemRspVO.builder()
                        .commentId(802L)
                        .nickname("redis-second")
                        .content("second body")
                        .heat(6.2)
                        .build())));

        var firstResponse = commentService.findCommentPageList(request());
        var secondResponse = commentService.findCommentPageList(request());

        assertThat(firstResponse.getData()).extracting(FindCommentItemRspVO::getCommentId)
                .containsExactly(801L, 802L);
        assertThat(secondResponse.getData()).extracting(FindCommentItemRspVO::getCommentId)
                .containsExactly(801L, 802L);
        verify(valueOperations, times(1)).multiGet(List.of(RedisKeyConstants.buildCommentDetailKey(802L)));
    }

    @Test
    void preservesRedisZsetOrderWhenCachedDetailsHaveStaleHeatValues() {
        putLocalComment(801L, "first", "first body", 1.0);
        putLocalComment(802L, "second", "second body", 99.0);
        Set<Object> commentIds = orderedIds(802L, 801L);
        stubCachedCountAndHotCommentIds(commentIds, 2L);

        var response = commentService.findCommentPageList(request());

        assertThat(response.getData()).extracting(FindCommentItemRspVO::getCommentId)
                .containsExactly(802L, 801L);
    }

    @Test
    void mapsPartialRedisMgetMissesToTheMatchingCommentId() {
        putLocalComment(801L, "local-first", "first body", 9.5);
        Set<Object> commentIds = orderedIds(801L, 802L, 803L);
        stubCachedCountAndHotCommentIds(commentIds, 3L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(List.of(RedisKeyConstants.buildCommentDetailKey(802L),
                RedisKeyConstants.buildCommentDetailKey(803L))))
                .thenReturn(java.util.Arrays.asList(
                        JsonUtils.toJsonString(FindCommentItemRspVO.builder()
                                .commentId(802L).content("second body").heat(8.0).build()), null));
        CommentDO thirdComment = CommentDO.builder()
                .id(803L)
                .userId(3L)
                .noteId(600L)
                .isContentEmpty(true)
                .firstReplyCommentId(0L)
                .createTime(LocalDateTime.now().minusMinutes(2))
                .likeTotal(0L)
                .childCommentTotal(0L)
                .heat(7.0)
                .build();
        when(commentDOMapper.selectByCommentIds(anyList())).thenAnswer(invocation ->
                List.of(803L).equals(invocation.getArgument(0)) ? List.of(thirdComment) : List.of());
        when(keyValueRpcService.batchFindCommentContent(eq(600L), anyList())).thenReturn(List.of());
        when(userRpcService.findByIds(List.of(3L))).thenReturn(List.of(
                FindUserByIdRspDTO.builder().id(3L).build()));

        var response = commentService.findCommentPageList(request());

        verify(commentDOMapper).selectByCommentIds(List.of(803L));
        assertThat(response.getData()).extracting(FindCommentItemRspVO::getCommentId)
                .containsExactly(801L, 802L, 803L);
    }

    @Test
    void doesNotCacheTextCommentWhenCassandraBodyIsMissing() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("count:note:600", "commentTotal")).thenReturn(1L);
        CommentDO comment = CommentDO.builder()
                .id(810L)
                .noteId(600L)
                .userId(3L)
                .contentUuid("missing-body-" + System.nanoTime())
                .isContentEmpty(false)
                .firstReplyCommentId(0L)
                .createTime(LocalDateTime.now().minusMinutes(1))
                .likeTotal(0L)
                .childCommentTotal(0L)
                .heat(1.0)
                .build();
        when(commentDOMapper.selectPageList(600L, 0L, 10L)).thenReturn(List.of(comment));
        when(keyValueRpcService.batchFindCommentContent(eq(600L), anyList())).thenReturn(List.of());
        when(userRpcService.findByIds(List.of(3L))).thenReturn(List.of(
                FindUserByIdRspDTO.builder().id(3L).build()));

        var response = commentService.findCommentPageList(request());

        assertThat(response.getData()).singleElement()
                .extracting(FindCommentItemRspVO::getCommentId).isEqualTo(810L);
        // The only scheduled task is the independent hot-list refresh. No detail cache write is scheduled.
        verify(threadPoolTaskExecutor, times(1)).execute(any(Runnable.class));
    }

    @Test
    void keepsRootCommentPageAvailableWhenBatchUserLookupReturnsNull() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("count:note:600", "commentTotal")).thenReturn(1L);
        CommentDO comment = CommentDO.builder()
                .id(811L)
                .noteId(600L)
                .userId(3L)
                .isContentEmpty(true)
                .firstReplyCommentId(0L)
                .createTime(LocalDateTime.now().minusMinutes(1))
                .likeTotal(0L)
                .childCommentTotal(0L)
                .heat(1.0)
                .build();
        when(commentDOMapper.selectPageList(600L, 0L, 10L)).thenReturn(List.of(comment));
        when(keyValueRpcService.batchFindCommentContent(eq(600L), anyList())).thenReturn(List.of());
        when(userRpcService.findByIds(List.of(3L))).thenReturn(null);

        var response = commentService.findCommentPageList(request());

        assertThat(response.getData()).singleElement().satisfies(item -> {
            assertThat(item.getCommentId()).isEqualTo(811L);
            assertThat(item.getUserId()).isEqualTo(3L);
            assertThat(item.getNickname()).isNull();
        });
    }

    private void stubCachedCountAndHotCommentIds(Set<Object> commentIds, long count) {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("count:note:600", "commentTotal")).thenReturn(count);
        lenient().when(redisTemplate.executePipelined(any(SessionCallback.class))).thenReturn(
                commentIds.stream().map(ignored -> (Object) Map.of()).toList());
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(commentIds.stream()
                        .map(id -> RedisKeyConstants.buildHotCommentMember(Long.valueOf(id.toString())))
                        .toList());
    }

    private static void putLocalComment(Long id, String nickname, String content, Double heat) {
        Object localCache = ReflectionTestUtils.getField(CommentServiceImpl.class, "LOCAL_CACHE");
        if (localCache != null) {
            ReflectionTestUtils.invokeMethod(localCache, "put", id, JsonUtils.toJsonString(FindCommentItemRspVO.builder()
                    .commentId(id)
                    .nickname(nickname)
                    .content(content)
                    .heat(heat)
                    .build()));
        }
    }

    private static Set<Object> orderedIds(Long... ids) {
        Set<Object> commentIds = new LinkedHashSet<>();
        commentIds.addAll(List.of(ids));
        return commentIds;
    }

    private static FindCommentPageListReqVO request() {
        return FindCommentPageListReqVO.builder()
                .noteId(600L)
                .pageNo(1)
                .build();
    }
}
