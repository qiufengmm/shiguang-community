package com.quanxiaoha.xiaohashu.note.biz.service;

import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteOperateOutboxDO;
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

class NoteOperateOutboxRelayTest {

    @Test
    void schedulesRetryAndKeepsOutboxPendingWhenBrokerSendFails() {
        NoteOperateOutboxService outboxService = mock(NoteOperateOutboxService.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        NoteOperateOutboxDO record = NoteOperateOutboxDO.builder()
                .id(18L)
                .eventId("note-operate:201:1")
                .topic("NoteOperateTopic:publishNote")
                .payload("payload")
                .attemptCount(1)
                .leaseOwner("lease-owner")
                .leaseVersion(2L)
                .build();
        when(outboxService.claimBatch(any(String.class))).thenReturn(List.of(record));
        when(rocketMQTemplate.syncSend(eq(record.getTopic()), any(org.springframework.messaging.Message.class)))
                .thenThrow(new IllegalStateException("broker unavailable"));

        NoteOperateOutboxRelay relay = new NoteOperateOutboxRelay();
        ReflectionTestUtils.setField(relay, "outboxService", outboxService);
        ReflectionTestUtils.setField(relay, "rocketMQTemplate", rocketMQTemplate);

        relay.relayBatch();

        verify(outboxService).scheduleRetry(eq(record), any(IllegalStateException.class));
        verify(outboxService, never()).markSent(record);
    }
}
