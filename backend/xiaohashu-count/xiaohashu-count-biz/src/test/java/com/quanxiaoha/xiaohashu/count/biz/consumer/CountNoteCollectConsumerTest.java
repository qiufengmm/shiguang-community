package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.count.biz.model.dto.AggregationCountCollectUnCollectNoteMqDTO;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CountNoteCollectConsumerTest {

    private RedisTemplate<String, Object> redisTemplate;
    private RocketMQTemplate rocketMQTemplate;
    private HashOperations<String, Object, Object> hashOperations;
    private CountNoteCollectConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        rocketMQTemplate = mock(RocketMQTemplate.class);
        hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        consumer = new CountNoteCollectConsumer();
        ReflectionTestUtils.setField(consumer, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(consumer, "rocketMQTemplate", rocketMQTemplate);
    }

    @AfterEach
    void tearDown() {
        consumer.flushOnShutdown();
    }

    @Test
    void aggregatesCollectAndUnCollectPerNote() {
        List<AggregationCountCollectUnCollectNoteMqDTO> countList = consumer.aggregate(List.of(
                "{\"userId\":1,\"noteId\":201,\"type\":1,\"noteCreatorId\":301}",
                "{\"userId\":2,\"noteId\":201,\"type\":1,\"noteCreatorId\":301}",
                "{\"userId\":3,\"noteId\":201,\"type\":0,\"noteCreatorId\":301}"));

        assertEquals(1, countList.size());
        assertEquals(201L, countList.get(0).getNoteId());
        assertEquals(301L, countList.get(0).getCreatorId());
        assertEquals(1, countList.get(0).getCount());
    }

    @Test
    void dropsNotesWhoseNetDeltaIsZero() {
        List<AggregationCountCollectUnCollectNoteMqDTO> countList = consumer.aggregate(List.of(
                "{\"userId\":1,\"noteId\":201,\"type\":1,\"noteCreatorId\":301}",
                "{\"userId\":2,\"noteId\":201,\"type\":0,\"noteCreatorId\":301}"));

        assertTrue(countList.isEmpty());
    }

    @Test
    void incrementsBothNoteAndAuthorCaches() {
        when(redisTemplate.hasKey("count:note:201")).thenReturn(true);
        when(redisTemplate.hasKey("count:user:301")).thenReturn(true);

        consumer.consumeBatch(List.of(
                "{\"userId\":1,\"noteId\":201,\"type\":1,\"noteCreatorId\":301}",
                "{\"userId\":2,\"noteId\":201,\"type\":1,\"noteCreatorId\":301}"));

        verify(hashOperations).increment("count:note:201", "collectTotal", 2L);
        verify(hashOperations).increment("count:user:301", "collectTotal", 2L);
        verify(rocketMQTemplate).syncSend(eq(MQConstants.TOPIC_COUNT_NOTE_COLLECT_2_DB), any(Message.class));
    }

    @Test
    void publishesNothingWhenEveryNoteNetsToZero() {
        consumer.consumeBatch(List.of(
                "{\"userId\":1,\"noteId\":201,\"type\":1,\"noteCreatorId\":301}",
                "{\"userId\":2,\"noteId\":201,\"type\":0,\"noteCreatorId\":301}"));

        verify(rocketMQTemplate, never()).syncSend(any(String.class), any(Message.class));
    }
}
