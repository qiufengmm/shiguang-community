package com.quanxiaoha.xiaohashu.comment.biz.consumer;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentDO;
import com.quanxiaoha.xiaohashu.comment.biz.service.CommentInboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DeleteCommentConsumerTest {

    private final CommentInboxService commentInboxService = mock(CommentInboxService.class);
    private DeleteCommentConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new DeleteCommentConsumer();
        ReflectionTestUtils.setField(consumer, "commentInboxService", commentInboxService);
    }

    @Test
    void persistsDeleteBeforeAcknowledgingMessage() {
        String payload = JsonUtils.toJsonString(CommentDO.builder()
                .id(110L)
                .noteId(900L)
                .level(1)
                .build());

        consumer.onMessage(payload);

        verify(commentInboxService).receive("comment-delete:110", "DELETE", payload);
    }

    @Test
    void rejectsDeleteEventWithoutCommentId() {
        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage("{}"));
    }
}
