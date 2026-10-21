package com.quanxiaoha.xiaohashu.comment.biz.service;

import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentOutboxDO;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentOutboxRelayTest {

    @Test
    void schedulesRetryAndDoesNotMarkSentWhenBrokerSendFails() {
        CommentOutboxService outboxService = mock(CommentOutboxService.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        CommentOutboxDO record = CommentOutboxDO.builder()
                .id(88L)
                .eventKey("comment-publish:test-88")
                .topic("PublishCommentTopic")
                .payload("payload")
                .leaseOwner("lease-owner")
                .leaseVersion(1L)
                .build();
        when(outboxService.claimBatch(any(String.class))).thenReturn(List.of(record));
        when(rocketMQTemplate.syncSend(eq(record.getTopic()), any(org.springframework.messaging.Message.class)))
                .thenThrow(new IllegalStateException("broker unavailable"));

        CommentOutboxRelay relay = new CommentOutboxRelay();
        ReflectionTestUtils.setField(relay, "commentOutboxService", outboxService);
        ReflectionTestUtils.setField(relay, "rocketMQTemplate", rocketMQTemplate);

        int processed = relay.relayBatch();

        org.assertj.core.api.Assertions.assertThat(processed).isEqualTo(1);
        verify(outboxService).scheduleRetry(eq(record), any(IllegalStateException.class));
        verify(outboxService, never()).markSent(record);
    }
}
