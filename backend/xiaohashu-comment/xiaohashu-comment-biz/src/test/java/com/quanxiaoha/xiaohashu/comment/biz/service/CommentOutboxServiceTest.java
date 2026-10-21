package com.quanxiaoha.xiaohashu.comment.biz.service;

import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentOutboxDO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentOutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentOutboxServiceTest {

    private CommentOutboxMapper mapper;
    private CommentOutboxService service;

    @BeforeEach
    void setUp() {
        mapper = mock(CommentOutboxMapper.class);
        service = new CommentOutboxService();
        ReflectionTestUtils.setField(service, "commentOutboxMapper", mapper);
    }

    @Test
    void insertsAnOutboxEventUsingItsStableKey() {
        when(mapper.insertIfAbsent(any(CommentOutboxDO.class))).thenReturn(1);

        service.enqueue("comment-publish:501", "PublishCommentTopic", "payload");

        verify(mapper).insertIfAbsent(any(CommentOutboxDO.class));
    }

    @Test
    void acceptsAnAlreadySentEventAfterItsPayloadWasScrubbed() {
        when(mapper.insertIfAbsent(any(CommentOutboxDO.class))).thenReturn(0);
        when(mapper.selectByEventKey("comment-publish:501"))
                .thenReturn(CommentOutboxDO.builder()
                        .eventKey("comment-publish:501")
                        .topic("PublishCommentTopic")
                        .status("SENT")
                        .payload("")
                        .build());

        service.enqueue("comment-publish:501", "PublishCommentTopic", "original-payload");
    }

    @Test
    void acceptsAnExactReplayButRejectsAStableKeyWithDifferentPayload() {
        when(mapper.insertIfAbsent(any(CommentOutboxDO.class))).thenReturn(0);
        when(mapper.selectByEventKey("comment-publish:501"))
                .thenReturn(CommentOutboxDO.builder()
                        .eventKey("comment-publish:501")
                        .topic("PublishCommentTopic")
                        .payload("different-payload")
                        .build());

        assertThatThrownBy(() -> service.enqueue(
                "comment-publish:501", "PublishCommentTopic", "payload"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("event key already belongs to another payload");
    }
}
