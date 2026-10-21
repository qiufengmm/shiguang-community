package com.quanxiaoha.xiaohashu.comment.biz.consumer;

import com.quanxiaoha.xiaohashu.comment.biz.service.CommentService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DeleteCommentLocalCacheConsumerTest {

    @Test
    void invalidatesCommentIdFromLocalCache() {
        CommentService commentService = mock(CommentService.class);
        DeleteCommentLocalCacheConsumer consumer = new DeleteCommentLocalCacheConsumer();
        ReflectionTestUtils.setField(consumer, "commentService", commentService);

        consumer.onMessage("9834712345");

        verify(commentService).deleteCommentLocalCache(9834712345L);
    }
}
