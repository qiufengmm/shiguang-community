package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CountFansConsumerTest {

    private RedisTemplate<String, Object> redisTemplate;
    private RocketMQTemplate rocketMQTemplate;
    private HashOperations<String, Object, Object> hashOperations;
    private CountFansConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        rocketMQTemplate = mock(RocketMQTemplate.class);
        hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        consumer = new CountFansConsumer();
        ReflectionTestUtils.setField(consumer, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(consumer, "rocketMQTemplate", rocketMQTemplate);
    }

    @AfterEach
    void tearDown() {
        consumer.flushOnShutdown();
    }

    @Test
    void aggregatesFollowAndUnfollowIntoNetDelta() {
        Map<Long, Integer> countMap = consumer.aggregate(List.of(
                "{\"userId\":1,\"targetUserId\":29,\"type\":1}",
                "{\"userId\":2,\"targetUserId\":29,\"type\":1}",
                "{\"userId\":3,\"targetUserId\":29,\"type\":0}"));

        assertEquals(Map.of(29L, 1), countMap);
    }

    @Test
    void dropsUsersWhoseNetDeltaIsZero() {
        Map<Long, Integer> countMap = consumer.aggregate(List.of(
                "{\"userId\":1,\"targetUserId\":29,\"type\":1}",
                "{\"userId\":2,\"targetUserId\":29,\"type\":0}"));

        assertTrue(countMap.isEmpty(), "同一批内先关注又取关，净额为 0，不应产生落库消息");
    }

    @Test
    void skipsMalformedEventsInsteadOfFailingTheWholeBatch() {
        Map<Long, Integer> countMap = consumer.aggregate(List.of(
                "not-a-json",
                "{\"userId\":1,\"type\":1}",
                "{\"userId\":2,\"targetUserId\":29,\"type\":9}",
                "{\"userId\":3,\"targetUserId\":29,\"type\":1}"));

        assertEquals(Map.of(29L, 1), countMap);
    }

    @Test
    void writesRedisThenPublishesAggregatedBatch() {
        when(redisTemplate.hasKey("count:user:29")).thenReturn(true);

        consumer.consumeBatch(List.of(
                "{\"userId\":1,\"targetUserId\":29,\"type\":1}",
                "{\"userId\":2,\"targetUserId\":29,\"type\":1}"));

        verify(hashOperations).increment("count:user:29", "fansTotal", 2L);
        verify(rocketMQTemplate).syncSend(eq(MQConstants.TOPIC_COUNT_FANS_2_DB), any(Message.class));
    }

    @Test
    void doesNotCreateCacheForUnwarmedUser() {
        when(redisTemplate.hasKey("count:user:29")).thenReturn(false);

        consumer.consumeBatch(List.of("{\"userId\":1,\"targetUserId\":29,\"type\":1}"));

        verifyNoInteractions(hashOperations);
        verify(rocketMQTemplate).syncSend(eq(MQConstants.TOPIC_COUNT_FANS_2_DB), any(Message.class));
    }

    @Test
    void publishesNothingWhenEveryUserNetsToZero() {
        consumer.consumeBatch(List.of(
                "{\"userId\":1,\"targetUserId\":29,\"type\":1}",
                "{\"userId\":2,\"targetUserId\":29,\"type\":0}"));

        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class));
        verifyNoInteractions(hashOperations);
    }
}
