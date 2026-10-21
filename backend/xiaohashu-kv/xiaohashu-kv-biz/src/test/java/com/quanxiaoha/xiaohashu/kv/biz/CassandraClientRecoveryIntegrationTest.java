package com.quanxiaoha.xiaohashu.kv.biz;

import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Read-only recovery probe; it never writes to the shared Cassandra data store. */
class CassandraClientRecoveryIntegrationTest {

    @Test
    @Timeout(30)
    void freshDriverSessionConnectsAfterAnIsolatedEndpointRefusal() {
        assumeTrue(Boolean.parseBoolean(System.getenv("XIAOHASHU_TEST_CASSANDRA")),
                "set XIAOHASHU_TEST_CASSANDRA=true to run the real Cassandra probe");

        assertThatThrownBy(() -> openSession(1))
                .isInstanceOf(RuntimeException.class);

        int healthyPort = Integer.parseInt(System.getenv().getOrDefault("XIAOHASHU_TEST_CASSANDRA_PORT", "9042"));
        try (CqlSession session = openSession(healthyPort)) {
            assertThat(session.execute("SELECT release_version FROM system.local").one())
                    .isNotNull();
            assertThat(session.execute("SELECT release_version FROM system.local").one()
                    .getString("release_version")).isNotBlank();
        }
    }

    @Test
    @Timeout(180)
    void existingDriverSessionRecoversAfterAnIsolatedCassandraRestart() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getenv("XIAOHASHU_TEST_CASSANDRA_RESTART")),
                "set XIAOHASHU_TEST_CASSANDRA_RESTART=true for the isolated Docker restart probe");

        String container = System.getenv().getOrDefault("XIAOHASHU_TEST_CASSANDRA_CONTAINER", "xh-fault-cassandra");
        assertIsolatedFixture(container);
        int port = Integer.parseInt(System.getenv().getOrDefault("XIAOHASHU_TEST_CASSANDRA_PORT", "19042"));

        try (CqlSession session = openSession(port)) {
            assertThat(readReleaseVersion(session)).isNotBlank();
            docker("stop", "--time", "5", container);
            try {
                assertThatThrownBy(() -> readReleaseVersion(session))
                        .as("the established session must observe the isolated node outage")
                        .isInstanceOf(RuntimeException.class);
            } finally {
                docker("start", container);
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            RuntimeException lastFailure = null;
            while (System.nanoTime() < deadline) {
                try {
                    if (readReleaseVersion(session) != null) {
                        return;
                    }
                } catch (RuntimeException e) {
                    lastFailure = e;
                }
                Thread.sleep(1_000);
            }
            throw new AssertionError("Cassandra driver session did not recover after the isolated container restart",
                    lastFailure);
        } finally {
            docker("start", container);
        }
    }

    private static CqlSession openSession(int port) {
        return CqlSession.builder()
                .addContactPoint(new InetSocketAddress("127.0.0.1", port))
                .withLocalDatacenter("datacenter1")
                .build();
    }

    private static String readReleaseVersion(CqlSession session) {
        return session.execute("SELECT release_version FROM system.local").one().getString("release_version");
    }

    private static void assertIsolatedFixture(String container) throws Exception {
        String labels = docker("inspect", "--format", "{{.Config.Labels}}", container).trim();
        assertThat(labels).contains("codex.test.fixture:dependency-recovery");
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
