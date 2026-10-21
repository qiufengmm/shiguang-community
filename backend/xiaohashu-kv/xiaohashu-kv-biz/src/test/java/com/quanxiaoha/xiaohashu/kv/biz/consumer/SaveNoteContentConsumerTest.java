package com.quanxiaoha.xiaohashu.kv.biz.consumer;

import com.quanxiaoha.xiaohashu.kv.biz.service.NoteContentService;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SaveNoteContentConsumerTest {

    @Mock
    private NoteContentService noteContentService;

    @InjectMocks
    private SaveNoteContentConsumer consumer;

    @Test
    void delayedPublishTransactionMessageDoesNotOverwriteTheSynchronousContent() {
        String body = "{\"id\":991234567890111,\"title\":\"transaction note\",\"isContentEmpty\":false"
                + ",\"creatorId\":992345678901122,\"contentUuid\":\"11111111-1111-4111-8111-111111111111\""
                + ",\"content\":\"cassandra body fixture\"}";
        Message message = new Message("PublishNoteTransactionTopic", body.getBytes(StandardCharsets.UTF_8));

        consumer.onMessage(message);

        verifyNoInteractions(noteContentService);
    }
}
