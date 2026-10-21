package com.quanxiaoha.xiaohashu.search.biz.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.exception.CanalClientException;
import com.alibaba.otter.canal.protocol.Message;
import com.quanxiaoha.xiaohashu.search.biz.service.DocumentIndexRefreshService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanalScheduleRecoveryTest {

    @Test
    void invalidatesTheSocketAndWaitsForBackoffWhenTheBatchPollBreaks() throws Exception {
        CanalClient canalClient = mock(CanalClient.class);
        CanalConnector connector = mock(CanalConnector.class);
        when(canalClient.getCurrentConnector()).thenReturn(connector, null);
        when(connector.getWithoutAck(anyInt())).thenThrow(new CanalClientException("broken pipe"));

        CanalSchedule schedule = newSchedule(canalClient);

        schedule.run();
        schedule.run();

        verify(canalClient).invalidateConnection(connector);
        verify(canalClient, never()).reconnect();
    }

    @Test
    void invalidatesTheSocketWhenAcknowledgementFailsEvenIfTheBatchCanBeRolledBack() throws Exception {
        CanalClient canalClient = mock(CanalClient.class);
        CanalConnector connector = mock(CanalConnector.class);
        Message message = mock(Message.class);
        when(canalClient.getCurrentConnector()).thenReturn(connector);
        when(connector.getWithoutAck(anyInt())).thenReturn(message);
        when(message.getId()).thenReturn(42L);
        when(message.getEntries()).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new CanalClientException("broken pipe"))
                .when(connector).ack(42L);

        CanalSchedule schedule = newSchedule(canalClient);

        schedule.run();

        verify(canalClient).invalidateConnection(connector);
        verify(connector, never()).rollback(42L);
    }

    @Test
    void doesNotReconnectAgainImmediatelyWhenAReplacementSocketBreaks() throws Exception {
        CanalClient canalClient = mock(CanalClient.class);
        CanalConnector first = mock(CanalConnector.class);
        CanalConnector replacement = mock(CanalConnector.class);
        when(canalClient.getCurrentConnector()).thenReturn(first, null, null, replacement, null);
        when(canalClient.reconnect()).thenReturn(replacement);
        when(first.getWithoutAck(anyInt())).thenThrow(new CanalClientException("first broken pipe"));
        when(replacement.getWithoutAck(anyInt())).thenThrow(new CanalClientException("second broken pipe"));

        CanalSchedule schedule = newSchedule(canalClient);

        schedule.run();
        schedule.run();
        ReflectionTestUtils.setField(schedule, "nextReconnectAttemptAt", 0L);
        schedule.run();
        schedule.run();
        schedule.run();

        verify(canalClient).reconnect();
        verify(canalClient).invalidateConnection(first);
        verify(canalClient).invalidateConnection(replacement);
    }

    private static CanalSchedule newSchedule(CanalClient canalClient) {
        CanalSchedule schedule = new CanalSchedule();
        ReflectionTestUtils.setField(schedule, "canalClient", canalClient);
        ReflectionTestUtils.setField(schedule, "documentIndexRefreshService",
                mock(DocumentIndexRefreshService.class));
        return schedule;
    }
}
