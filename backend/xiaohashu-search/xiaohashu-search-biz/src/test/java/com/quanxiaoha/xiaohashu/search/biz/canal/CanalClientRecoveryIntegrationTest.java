package com.quanxiaoha.xiaohashu.search.biz.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.client.impl.SimpleCanalConnector;
import com.alibaba.otter.canal.protocol.ClientIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Uses a private Canal client ID and does not fetch or acknowledge batches, so the
 * running search service's destination checkpoint is not changed.
 */
class CanalClientRecoveryIntegrationTest {

    private static final short ISOLATED_CLIENT_ID =
            (short) (20_000 + Math.floorMod(System.nanoTime(), 7_000L));

    @Test
    @Timeout(30)
    void clientCanReconnectAfterAnIsolatedConnectionRefusal() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getenv("XIAOHASHU_TEST_CANAL")),
                "set XIAOHASHU_TEST_CANAL=true to run the real Canal probe");

        CanalProperties properties = new CanalProperties();
        properties.setAddress("127.0.0.1:1");
        properties.setDestination("example");
        properties.setSubscribe("xiaohashu.t_note,xiaohashu.t_user,xiaohashu.t_note_count,xiaohashu.t_user_count");

        CanalClient client = new CanalClient() {
            @Override
            protected CanalConnector createConnector() {
                String[] address = properties.getAddress().split(":");
                SimpleCanalConnector connector = (SimpleCanalConnector) CanalConnectors.newSingleConnector(
                        new InetSocketAddress(address[0], Integer.parseInt(address[1])),
                        properties.getDestination(), properties.getUsername(), properties.getPassword());
                ReflectionTestUtils.setField(connector, "clientIdentity",
                        new ClientIdentity(properties.getDestination(), ISOLATED_CLIENT_ID));
                return connector;
            }
        };
        ReflectionTestUtils.setField(client, "canalProperties", properties);

        try {
            assertThatThrownBy(client::getCanalConnector).isInstanceOf(RuntimeException.class);
            assertThat(client.getCurrentConnector()).isNull();

            properties.setAddress(System.getenv().getOrDefault("XIAOHASHU_TEST_CANAL_ADDR", "127.0.0.1:11111"));
            CanalConnector recovered = client.reconnect();
            assertThat(recovered).isNotNull();
            assertThat(client.getCurrentConnector()).isSameAs(recovered);
        } finally {
            client.destroy();
        }
    }

    @Test
    @Timeout(120)
    void clientCanReconnectAfterAnIsolatedCanalRestart() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getenv("XIAOHASHU_TEST_CANAL_RESTART")),
                "set XIAOHASHU_TEST_CANAL_RESTART=true for the isolated Docker restart probe");

        String containerName = System.getenv().getOrDefault("XIAOHASHU_TEST_CANAL_CONTAINER", "xh-fault-canal");
        String labels = docker("inspect", "--format", "{{.Config.Labels}}", containerName).trim();
        assertThat(labels).contains("codex.test.fixture:dependency-recovery");

        CanalProperties properties = new CanalProperties();
        String address = System.getenv().getOrDefault("XIAOHASHU_TEST_CANAL_ADDR", "127.0.0.1:19111");
        properties.setAddress(address);
        properties.setDestination("example");
        properties.setSubscribe("xiaohashu.t_note,xiaohashu.t_user,xiaohashu.t_note_count,xiaohashu.t_user_count");
        CanalClient client = newClient(properties);

        try {
            CanalConnector initial = client.getCanalConnector();
            assertThat(client.getCurrentConnector()).isSameAs(initial);
            docker("stop", "--time", "5", containerName);
            try {
                assertThatThrownBy(client::reconnect)
                        .as("the isolated Canal outage should reject a new client connection")
                        .isInstanceOf(RuntimeException.class);
            } finally {
                docker("start", containerName);
            }

            awaitPort(address, 90);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            RuntimeException lastFailure = null;
            while (System.nanoTime() < deadline) {
                try {
                    CanalConnector recovered = client.reconnect();
                    assertThat(recovered).isNotNull();
                    assertThat(client.getCurrentConnector()).isSameAs(recovered);
                    return;
                } catch (RuntimeException e) {
                    lastFailure = e;
                    Thread.sleep(1_000);
                }
            }
            throw new AssertionError("Canal client did not reconnect after the isolated container restart",
                    lastFailure);
        } finally {
            client.destroy();
            docker("start", containerName);
        }
    }

    private static CanalClient newClient(CanalProperties properties) {
        CanalClient client = new CanalClient() {
            @Override
            protected CanalConnector createConnector() {
                String[] address = properties.getAddress().split(":");
                SimpleCanalConnector connector = (SimpleCanalConnector) CanalConnectors.newSingleConnector(
                        new InetSocketAddress(address[0], Integer.parseInt(address[1])),
                        properties.getDestination(), properties.getUsername(), properties.getPassword());
                ReflectionTestUtils.setField(connector, "clientIdentity",
                        new ClientIdentity(properties.getDestination(), ISOLATED_CLIENT_ID));
                return connector;
            }
        };
        ReflectionTestUtils.setField(client, "canalProperties", properties);
        return client;
    }

    private static void awaitPort(String address, int timeoutSeconds) throws Exception {
        String[] hostPort = address.split(":");
        InetSocketAddress endpoint = new InetSocketAddress(hostPort[0], Integer.parseInt(hostPort[1]));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(endpoint, 500);
                return;
            } catch (IOException ignored) {
                Thread.sleep(500);
            }
        }
        throw new AssertionError("Canal did not reopen its TCP port after the isolated container restart");
    }

    private static String docker(String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = "docker";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Docker command timed out: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Docker command failed: " + String.join(" ", command) + "\n"
                    + new String(output, StandardCharsets.UTF_8));
        }
        return new String(output, StandardCharsets.UTF_8);
    }
}
