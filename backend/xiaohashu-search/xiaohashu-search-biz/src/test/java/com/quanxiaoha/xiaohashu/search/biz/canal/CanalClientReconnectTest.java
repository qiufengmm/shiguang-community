package com.quanxiaoha.xiaohashu.search.biz.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayDeque;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CanalClientReconnectTest {

    @Test
    void reconnectDisconnectsOldConnectorAndSubscribesTheReplacement() {
        CanalConnector first = mock(CanalConnector.class);
        CanalConnector replacement = mock(CanalConnector.class);
        Queue<CanalConnector> connectors = new ArrayDeque<>();
        connectors.add(first);
        connectors.add(replacement);
        CanalClient canalClient = new TestCanalClient(connectors);
        CanalProperties properties = new CanalProperties();
        properties.setAddress("canal:11111");
        properties.setDestination("example");
        properties.setUsername("canal-user");
        properties.setPassword("canal-password");
        properties.setSubscribe("xiaohashu.t_note");
        ReflectionTestUtils.setField(canalClient, "canalProperties", properties);

        CanalConnector initial = canalClient.getCanalConnector();
        CanalConnector current = canalClient.reconnect();

        assertThat(initial).isSameAs(first);
        assertThat(current).isSameAs(replacement);
        verify(first).disconnect();
        verify(replacement).connect();
        verify(replacement).subscribe("xiaohashu.t_note");
        verify(replacement).rollback();
    }

    @Test
    void invalidatingTheCurrentConnectorDisconnectsItAndClearsTheReference() {
        CanalConnector connector = mock(CanalConnector.class);
        Queue<CanalConnector> connectors = new ArrayDeque<>();
        connectors.add(connector);
        CanalClient canalClient = new TestCanalClient(connectors);
        CanalProperties properties = new CanalProperties();
        properties.setAddress("canal:11111");
        properties.setDestination("example");
        ReflectionTestUtils.setField(canalClient, "canalProperties", properties);

        canalClient.getCanalConnector();
        canalClient.invalidateConnection(connector);

        assertThat(canalClient.getCurrentConnector()).isNull();
        verify(connector).disconnect();
    }

    private static class TestCanalClient extends CanalClient {
        private final Queue<CanalConnector> connectors;

        private TestCanalClient(Queue<CanalConnector> connectors) {
            this.connectors = connectors;
        }

        @Override
        protected CanalConnector createConnector() {
            return connectors.remove();
        }
    }
}
