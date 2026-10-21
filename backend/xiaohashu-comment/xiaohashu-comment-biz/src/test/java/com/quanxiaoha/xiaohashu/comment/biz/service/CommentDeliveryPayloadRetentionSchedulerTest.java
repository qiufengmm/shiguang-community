package com.quanxiaoha.xiaohashu.comment.biz.service;

import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentInboxMapper;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentOutboxMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentDeliveryPayloadRetentionSchedulerTest {

    @Test
    void scrubsOnlyOldTerminalPayloadsInBoundedBatches() {
        CommentInboxMapper inboxMapper = mock(CommentInboxMapper.class);
        CommentOutboxMapper outboxMapper = mock(CommentOutboxMapper.class);
        when(inboxMapper.scrubAppliedPayloadsBefore(any(), eq(500))).thenReturn(500, 2);
        when(outboxMapper.scrubSentPayloadsBefore(any(), eq(500))).thenReturn(1);

        CommentDeliveryPayloadRetentionScheduler scheduler = new CommentDeliveryPayloadRetentionScheduler();
        ReflectionTestUtils.setField(scheduler, "commentInboxMapper", inboxMapper);
        ReflectionTestUtils.setField(scheduler, "commentOutboxMapper", outboxMapper);
        ReflectionTestUtils.setField(scheduler, "payloadRetentionDays", 30);

        LocalDateTime before = LocalDateTime.now().minusDays(30).minusSeconds(2);
        scheduler.scrubOldTerminalPayloads();
        LocalDateTime after = LocalDateTime.now().minusDays(30).plusSeconds(2);

        org.mockito.ArgumentCaptor<LocalDateTime> inboxCutoff =
                org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.ArgumentCaptor<LocalDateTime> outboxCutoff =
                org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(inboxMapper, times(2)).scrubAppliedPayloadsBefore(inboxCutoff.capture(), eq(500));
        verify(outboxMapper).scrubSentPayloadsBefore(outboxCutoff.capture(), eq(500));
        assertThat(inboxCutoff.getAllValues()).allMatch(cutoff -> !cutoff.isBefore(before) && !cutoff.isAfter(after));
        assertThat(outboxCutoff.getValue()).isBetween(before, after);
    }
}
