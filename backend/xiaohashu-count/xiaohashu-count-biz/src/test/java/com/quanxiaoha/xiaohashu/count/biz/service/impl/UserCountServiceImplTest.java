package com.quanxiaoha.xiaohashu.count.biz.service.impl;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.biz.domain.dataobject.UserCountDO;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.UserCountDOMapper;
import com.quanxiaoha.xiaohashu.count.dto.FindUserCountsByIdReqDTO;
import com.quanxiaoha.xiaohashu.count.dto.FindUserCountsByIdRspDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserCountServiceImplTest {

    private static final Long USER_ID = 971234567890123L;

    private UserCountServiceImpl service;
    private UserCountDOMapper userCountDOMapper;
    private RedisTemplate<String, Object> redisTemplate;
    private HashOperations<String, Object, Object> hashOperations;
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new UserCountServiceImpl();
        userCountDOMapper = mock(UserCountDOMapper.class);
        redisTemplate = mock(RedisTemplate.class);
        hashOperations = mock(HashOperations.class);
        threadPoolTaskExecutor = mock(ThreadPoolTaskExecutor.class);

        ReflectionTestUtils.setField(service, "userCountDOMapper", userCountDOMapper);
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "threadPoolTaskExecutor", threadPoolTaskExecutor);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    void cacheHitReturnsAllFiveCountsWithoutDatabaseRead() {
        when(hashOperations.multiGet(any(), any())).thenReturn(List.of(5L, 3L, 14L, 4L, 9L));

        Response<FindUserCountsByIdRspDTO> response = service.findUserCountData(
                FindUserCountsByIdReqDTO.builder().userId(USER_ID).build());

        assertEquals(USER_ID, response.getData().getUserId());
        assertEquals(3L, response.getData().getFansTotal());
        assertEquals(4L, response.getData().getFollowingTotal());
        assertEquals(14L, response.getData().getNoteTotal());
        assertEquals(9L, response.getData().getLikeTotal());
        assertEquals(5L, response.getData().getCollectTotal());
        verifyNoInteractions(userCountDOMapper);
        verify(threadPoolTaskExecutor, never()).submit(any(Runnable.class));
    }

    @Test
    void missingCacheFieldsAreReadFromDatabaseAndDefaultToZeroWhenNoRowExists() {
        when(hashOperations.multiGet(any(), any())).thenReturn(Arrays.asList(null, null, null, null, null));

        Response<FindUserCountsByIdRspDTO> response = service.findUserCountData(
                FindUserCountsByIdReqDTO.builder().userId(USER_ID).build());

        assertEquals(USER_ID, response.getData().getUserId());
        assertEquals(0L, response.getData().getFansTotal());
        assertEquals(0L, response.getData().getFollowingTotal());
        assertEquals(0L, response.getData().getNoteTotal());
        assertEquals(0L, response.getData().getLikeTotal());
        assertEquals(0L, response.getData().getCollectTotal());
        verify(userCountDOMapper).selectByUserId(USER_ID);
        verify(threadPoolTaskExecutor, never()).submit(any(Runnable.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void partialCacheUsesCachedValuesAndBackfillsOnlyMissingFields() {
        when(hashOperations.multiGet(any(), any())).thenReturn(Arrays.asList(5L, null, 14L, null, 9L));
        when(userCountDOMapper.selectByUserId(USER_ID)).thenReturn(UserCountDO.builder()
                .userId(USER_ID).fansTotal(3L).followingTotal(4L).noteTotal(12L).likeTotal(8L).collectTotal(2L).build());

        Response<FindUserCountsByIdRspDTO> response = service.findUserCountData(
                FindUserCountsByIdReqDTO.builder().userId(USER_ID).build());

        assertEquals(3L, response.getData().getFansTotal());
        assertEquals(4L, response.getData().getFollowingTotal());
        assertEquals(14L, response.getData().getNoteTotal());
        assertEquals(9L, response.getData().getLikeTotal());
        assertEquals(5L, response.getData().getCollectTotal());

        org.mockito.ArgumentCaptor<Runnable> taskCaptor = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(threadPoolTaskExecutor).submit(taskCaptor.capture());

        RedisOperations operations = mock(RedisOperations.class);
        HashOperations cacheHashOperations = mock(HashOperations.class);
        when(operations.opsForHash()).thenReturn(cacheHashOperations);
        doAnswer(invocation -> {
            SessionCallback callback = invocation.getArgument(0);
            callback.execute(operations);
            return List.of();
        }).when(redisTemplate).executePipelined(any(SessionCallback.class));

        taskCaptor.getValue().run();

        String cacheKey = "count:user:" + USER_ID;
        verify(cacheHashOperations).putAll(cacheKey, Map.of(
                "fansTotal", 3L,
                "followingTotal", 4L));
        verify(operations).expire(eq(cacheKey), longThat(seconds -> seconds >= 3600 && seconds <= 7199),
                eq(TimeUnit.SECONDS));
    }
}
