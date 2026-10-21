package com.quanxiaoha.xiaohashu.note.biz.service;

import com.quanxiaoha.xiaohashu.note.biz.constant.MQConstants;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses read-only route lookups: it does not publish test messages or change broker data. */
class RocketMqClientRecoveryIntegrationTest {

    @Test
    @Timeout(30)
    void producerCanRecoverWithAHealthyNameServerAfterAnIsolatedConnectionFailure() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getenv("XIAOHASHU_TEST_ROCKETMQ")),
                "set XIAOHASHU_TEST_ROCKETMQ=true to run the real RocketMQ probe");

        DefaultMQProducer unavailableProducer = newProducer("127.0.0.1:1");
        try {
            unavailableProducer.start();
            assertThatThrownBy(() -> unavailableProducer.fetchPublishMessageQueues(MQConstants.TOPIC_NOTE_OPERATE))
                    .isInstanceOf(Exception.class);
        } finally {
            unavailableProducer.shutdown();
        }

        String namesrvAddr = System.getenv().getOrDefault("XIAOHASHU_TEST_ROCKETMQ_NAMESRV_ADDR", "127.0.0.1:9876");
        DefaultMQProducer recoveredProducer = newProducer(namesrvAddr);
        try {
            recoveredProducer.start();
            List<MessageQueue> queues = recoveredProducer.fetchPublishMessageQueues(MQConstants.TOPIC_NOTE_OPERATE);
            assertThat(queues).isNotEmpty();
        } finally {
            recoveredProducer.shutdown();
        }
    }

    @Test
    @Timeout(120)
    void producerCanSendAgainAfterAnIsolatedBrokerRestart() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getenv("XIAOHASHU_TEST_ROCKETMQ_RESTART")),
                "set XIAOHASHU_TEST_ROCKETMQ_RESTART=true for the isolated Docker restart probe");

        String container = System.getenv().getOrDefault("XIAOHASHU_TEST_ROCKETMQ_CONTAINER", "xh-fault-broker");
        assertIsolatedFixture(container);
        String namesrvAddr = System.getenv().getOrDefault("XIAOHASHU_TEST_ROCKETMQ_NAMESRV_ADDR", "127.0.0.1:19876");
        String topic = "CodexFaultRecovery" + UUID.randomUUID().toString().replace("-", "");
        DefaultMQProducer producer = newProducer(namesrvAddr);
        producer.setRetryTimesWhenSendFailed(0);
        try {
            producer.start();
            producer.createTopic("TBW102", topic, 4);
            assertThat(sendProbe(producer, topic).getSendStatus()).isEqualTo(SendStatus.SEND_OK);

            docker("stop", "--time", "5", container);
            try {
                assertThatThrownBy(() -> sendProbe(producer, topic))
                        .as("the isolated broker outage should make an in-flight send fail")
                        .isInstanceOf(Exception.class);
            } finally {
                docker("start", container);
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75);
            Exception lastFailure = null;
            while (System.nanoTime() < deadline) {
                try {
                    SendResult result = sendProbe(producer, topic);
                    if (result.getSendStatus() == SendStatus.SEND_OK) {
                        return;
                    }
                    lastFailure = new IllegalStateException("unexpected send status: " + result.getSendStatus());
                } catch (Exception e) {
                    lastFailure = e;
                }
                Thread.sleep(1_000);
            }
            throw new AssertionError("RocketMQ producer did not recover after the isolated broker restart", lastFailure);
        } finally {
            docker("start", container);
            producer.shutdown();
        }
    }

    private static DefaultMQProducer newProducer(String namesrvAddr) {
        DefaultMQProducer producer = new DefaultMQProducer("codexRecoveryProbe_" + UUID.randomUUID());
        producer.setNamesrvAddr(namesrvAddr);
        producer.setSendMsgTimeout(1_000);
        return producer;
    }

    private static SendResult sendProbe(DefaultMQProducer producer, String topic) throws Exception {
        return producer.send(new Message(topic, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
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
