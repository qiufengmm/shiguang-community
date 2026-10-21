package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.quanxiaoha.xiaohashu.count.biz.model.dto.NoteOperateMqDTO;
import com.quanxiaoha.xiaohashu.count.biz.service.CountNotePublishService;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CountNotePublishConsumerTest {

    private RedisTemplate<String, Object> redisTemplate;
    private CountNotePublishService countNotePublishService;
    private CountNotePublishConsumer consumer;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        countNotePublishService = mock(CountNotePublishService.class);
        consumer = new CountNotePublishConsumer();
        ReflectionTestUtils.setField(consumer, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(consumer, "countNotePublishService", countNotePublishService);
    }

    @Test
    void shouldApplyPublishDeltaAndInvalidateCachedCount() {
        consumer.onMessage(new Message("NoteOperateTopic", "publishNote",
                "{\"eventId\":\"note:201:publish\",\"creatorId\":301,\"noteId\":201,\"type\":1}"
                        .getBytes(StandardCharsets.UTF_8)));

        verify(countNotePublishService).apply(any(NoteOperateMqDTO.class), eq(1L));
        verify(redisTemplate).delete("count:user:301");
    }

    @Test
    void shouldApplyDeleteDeltaAndInvalidateCachedCount() {
        consumer.onMessage(new Message("NoteOperateTopic", "deleteNote",
                "{\"creatorId\":301,\"noteId\":201}"
                        .getBytes(StandardCharsets.UTF_8)));

        verify(countNotePublishService).apply(any(NoteOperateMqDTO.class), eq(-1L));
        verify(redisTemplate).delete("count:user:301");
    }
}
