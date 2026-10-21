package com.quanxiaoha.xiaohashu.comment.biz.service.impl;

import com.quanxiaoha.xiaohashu.comment.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentDO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.invokeMethod;

/**
 * Exercises the real shared Redis lock and atomic snapshot replacement with two
 * independently connected service instances. Set XIAOHASHU_TEST_REDIS_PASSWORD
 * to enable it; no application data is modified because the test uses a random note ID.
 */
class CommentHotRebuildRedisIntegrationTest {

    @Test
    void concurrentRebuildAttemptsAcrossServiceInstancesUseOneDatabaseSnapshot() throws Exception {
        String password = System.getenv("XIAOHASHU_TEST_REDIS_PASSWORD");
        assumeTrue(password != null && !password.isBlank(),
                "set XIAOHASHU_TEST_REDIS_PASSWORD to run the real Redis integration test");

        long noteId = Math.abs(System.nanoTime()) + 20_000_000_000L;
        long commentId = noteId + 1;
        String listKey = RedisKeyConstants.buildCommentListKey(noteId);
        List<String> keys = List.of(listKey,
                RedisKeyConstants.buildCommentListGenerationKey(noteId),
                RedisKeyConstants.buildCommentListReadyKey(noteId),
                RedisKeyConstants.buildCommentListRebuildLockKey(noteId));

        int serviceCount = 128;
        int redisClientCount = 16;
        List<LettuceConnectionFactory> factories = new ArrayList<>();
        List<StringRedisTemplate> stringTemplates = new ArrayList<>();
        List<RedisTemplate<String, Object>> redisTemplates = new ArrayList<>();
        List<CommentServiceImpl> services = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(serviceCount);
        CountDownLatch ready = new CountDownLatch(serviceCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        CountDownLatch contendersReturned = new CountDownLatch(serviceCount - 1);
        AtomicInteger queryCount = new AtomicInteger();

        try {
            for (int i = 0; i < redisClientCount; i++) {
                LettuceConnectionFactory factory = createConnectionFactory(password);
                factories.add(factory);
                factory.afterPropertiesSet();

                StringRedisTemplate stringRedis = new StringRedisTemplate(factory);
                stringRedis.afterPropertiesSet();
                stringTemplates.add(stringRedis);

                RedisTemplate<String, Object> redis = createRedisTemplate(factory);
                redis.afterPropertiesSet();
                redisTemplates.add(redis);
            }

            for (int i = 0; i < serviceCount; i++) {
                CommentDOMapper mapper = mock(CommentDOMapper.class);
                when(mapper.selectHeatComments(noteId)).thenAnswer(invocation -> {
                    if (queryCount.incrementAndGet() == 1) {
                        queryStarted.countDown();
                        if (!releaseQuery.await(15, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out waiting to release the winning rebuild");
                        }
                    }
                    return List.of(CommentDO.builder().id(commentId).heat(73.25D).build());
                });

                int clientIndex = i % redisClientCount;
                services.add(serviceWithRedis(mapper, redisTemplates.get(clientIndex),
                        stringTemplates.get(clientIndex)));
            }

            List<java.util.concurrent.Future<?>> attempts = new ArrayList<>();
            for (CommentServiceImpl service : services) {
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        try {
                            if (!start.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("timed out waiting for concurrent rebuild start");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("concurrent rebuild attempt was interrupted", e);
                        }
                        invokeMethod(service, "syncHeatComments2Redis", listKey, noteId);
                    } finally {
                        contendersReturned.countDown();
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(queryStarted.await(10, TimeUnit.SECONDS))
                    .as("one service should own the Redis lock and begin the database snapshot")
                    .isTrue();
            assertThat(contendersReturned.await(10, TimeUnit.SECONDS))
                    .as("all contenders except the lock owner should skip the rebuild")
                    .isTrue();
            assertThat(queryCount.get()).isEqualTo(1);

            releaseQuery.countDown();
            for (var attempt : attempts) {
                attempt.get(10, TimeUnit.SECONDS);
            }

            Set<ZSetOperations.TypedTuple<String>> snapshot = stringTemplates.get(0).opsForZSet()
                    .rangeWithScores(listKey, 0, -1);
            assertThat(snapshot).hasSize(1);
            assertThat(snapshot.iterator().next().getValue())
                    .isEqualTo(RedisKeyConstants.buildHotCommentMember(commentId));
            assertThat(snapshot.iterator().next().getScore()).isEqualTo(73.25D);
            assertThat(redisTemplates.get(0).opsForValue()
                    .get(RedisKeyConstants.buildCommentListReadyKey(noteId)).toString()).isEqualTo("0");
            assertThat(stringTemplates.get(0).hasKey(
                    RedisKeyConstants.buildCommentListRebuildLockKey(noteId))).isFalse();
        } finally {
            releaseQuery.countDown();
            if (!stringTemplates.isEmpty()) {
                stringTemplates.get(0).delete(keys);
            }
            if (!redisTemplates.isEmpty()) {
                redisTemplates.get(0).delete(keys);
            }
            executor.shutdownNow();
            factories.forEach(LettuceConnectionFactory::destroy);
        }
    }

    @Test
    void onlyOneOfTwoServiceInstancesRebuildsTheSameNoteSnapshot() throws Exception {
        String password = System.getenv("XIAOHASHU_TEST_REDIS_PASSWORD");
        assumeTrue(password != null && !password.isBlank(),
                "set XIAOHASHU_TEST_REDIS_PASSWORD to run the real Redis integration test");

        long noteId = Math.abs(System.nanoTime()) + 10_000_000_000L;
        long commentId = noteId + 1;
        String listKey = RedisKeyConstants.buildCommentListKey(noteId);
        List<String> keys = List.of(listKey,
                RedisKeyConstants.buildCommentListGenerationKey(noteId),
                RedisKeyConstants.buildCommentListReadyKey(noteId),
                RedisKeyConstants.buildCommentListRebuildLockKey(noteId));

        LettuceConnectionFactory firstFactory = createConnectionFactory(password);
        LettuceConnectionFactory secondFactory = createConnectionFactory(password);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        StringRedisTemplate firstStringRedis = new StringRedisTemplate(firstFactory);
        StringRedisTemplate secondStringRedis = new StringRedisTemplate(secondFactory);
        RedisTemplate<String, Object> firstRedis = createRedisTemplate(firstFactory);
        RedisTemplate<String, Object> secondRedis = createRedisTemplate(secondFactory);

        try {
            firstFactory.afterPropertiesSet();
            secondFactory.afterPropertiesSet();
            firstStringRedis.afterPropertiesSet();
            secondStringRedis.afterPropertiesSet();
            firstRedis.afterPropertiesSet();
            secondRedis.afterPropertiesSet();

            CommentDOMapper firstMapper = mock(CommentDOMapper.class);
            CommentDOMapper secondMapper = mock(CommentDOMapper.class);
            CountDownLatch queryStarted = new CountDownLatch(1);
            CountDownLatch allowQueryToFinish = new CountDownLatch(1);
            when(firstMapper.selectHeatComments(noteId)).thenAnswer(invocation -> {
                queryStarted.countDown();
                if (!allowQueryToFinish.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release the first rebuild");
                }
                return List.of(CommentDO.builder().id(commentId).heat(42.5D).build());
            });

            CommentServiceImpl firstService = serviceWithRedis(firstMapper, firstRedis, firstStringRedis);
            CommentServiceImpl secondService = serviceWithRedis(secondMapper, secondRedis, secondStringRedis);
            var firstRebuild = executor.submit(() -> invokeMethod(firstService, "syncHeatComments2Redis", listKey, noteId));

            assertThat(queryStarted.await(10, TimeUnit.SECONDS))
                    .as("the first instance should own the Redis lock and enter the SQL snapshot query")
                    .isTrue();
            invokeMethod(secondService, "syncHeatComments2Redis", listKey, noteId);
            allowQueryToFinish.countDown();
            firstRebuild.get(10, TimeUnit.SECONDS);

            verify(firstMapper, times(1)).selectHeatComments(noteId);
            verify(secondMapper, never()).selectHeatComments(noteId);
            Set<ZSetOperations.TypedTuple<String>> snapshot = firstStringRedis.opsForZSet()
                    .rangeWithScores(listKey, 0, -1);
            assertThat(snapshot).hasSize(1);
            assertThat(snapshot.iterator().next().getValue())
                    .isEqualTo(RedisKeyConstants.buildHotCommentMember(commentId));
            assertThat(snapshot.iterator().next().getScore()).isEqualTo(42.5D);
            assertThat(firstRedis.opsForValue().get(RedisKeyConstants.buildCommentListReadyKey(noteId)).toString())
                    .isEqualTo("0");
            assertThat(firstStringRedis.hasKey(RedisKeyConstants.buildCommentListRebuildLockKey(noteId)))
                    .isFalse();
        } finally {
            firstStringRedis.delete(keys);
            firstRedis.delete(keys);
            secondStringRedis.delete(keys);
            secondRedis.delete(keys);
            executor.shutdownNow();
            firstFactory.destroy();
            secondFactory.destroy();
        }
    }

    private static LettuceConnectionFactory createConnectionFactory(String password) {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration("127.0.0.1", 6379);
        configuration.setPassword(RedisPassword.of(password));
        return new LettuceConnectionFactory(configuration);
    }

    private static RedisTemplate<String, Object> createRedisTemplate(LettuceConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        Jackson2JsonRedisSerializer<Object> serializer = new Jackson2JsonRedisSerializer<>(Object.class);
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);
        return template;
    }

    private static CommentServiceImpl serviceWithRedis(CommentDOMapper mapper,
                                                        RedisTemplate<String, Object> redisTemplate,
                                                        StringRedisTemplate stringRedisTemplate) {
        CommentServiceImpl service = new CommentServiceImpl();
        ReflectionTestUtils.setField(service, "commentDOMapper", mapper);
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        return service;
    }
}
