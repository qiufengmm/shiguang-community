package com.quanxiaoha.xiaohashu.comment.biz.service;

import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentInboxDO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentInboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommentInboxServiceTest {

    private CommentInboxMapper mapper;
    private CommentInboxService service;

    @BeforeEach
    void setUp() {
        mapper = mock(CommentInboxMapper.class);
        service = new CommentInboxService();
        ReflectionTestUtils.setField(service, "commentInboxMapper", mapper);
    }

    @Test
    void acceptsARepeatedDeliveryOfTheSameDurableEvent() {
        when(mapper.insertIfAbsent(any(CommentInboxDO.class))).thenReturn(0);
        when(mapper.selectByEventKey("comment-publish:901"))
                .thenReturn(CommentInboxDO.builder()
                        .eventKey("comment-publish:901")
                        .eventType("PUBLISH")
                        .payload("same-payload")
                        .build());

        service.receive("comment-publish:901", "PUBLISH", "same-payload");
    }

    @Test
    void acceptsARepeatedDeliveryAfterAppliedPayloadWasScrubbed() {
        when(mapper.insertIfAbsent(any(CommentInboxDO.class))).thenReturn(0);
        when(mapper.selectByEventKey("comment-publish:901"))
                .thenReturn(CommentInboxDO.builder()
                        .eventKey("comment-publish:901")
                        .eventType("PUBLISH")
                        .status("APPLIED")
                        .payload("")
                        .build());

        service.receive("comment-publish:901", "PUBLISH", "original-payload");
    }

    @Test
    void rejectsAnEventKeyReusedForDifferentPayload() {
        when(mapper.insertIfAbsent(any(CommentInboxDO.class))).thenReturn(0);
        when(mapper.selectByEventKey("comment-publish:901"))
                .thenReturn(CommentInboxDO.builder()
                        .eventKey("comment-publish:901")
                        .eventType("PUBLISH")
                        .payload("original-payload")
                        .build());

        assertThatThrownBy(() -> service.receive("comment-publish:901", "PUBLISH", "changed-payload"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("event key already belongs to another payload");
    }
}
