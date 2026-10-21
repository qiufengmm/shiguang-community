package com.quanxiaoha.xiaohashu.comment.biz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.client.QueryResult;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only RocketMQ probe for replaying an exact comment event and querying its retry/DLQ copies.
 * The payload is accepted as base64 and is never written to stdout or a report.
 */
public final class CommentRecoveryProbe {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private CommentRecoveryProbe() {
    }

    public static void main(String[] args) {
        try {
            Map<String, String> options = parseOptions(args);
            JsonNode manifest = OBJECT_MAPPER.readTree(Files.readString(Path.of(required(options, "manifest"))));
            String runId = manifest.path("runId").asText();
            String projectName = manifest.path("projectName").asText();
            if (!runId.matches("accept-[0-9a-f]{12}") || !projectName.equals("xiaohashu-" + runId)) {
                throw new IllegalArgumentException("manifest identity does not match an acceptance run");
            }

            String operation = required(options, "operation");
            String nameserver = options.getOrDefault("nameserver", "rocketmq-namesrv:9876");
            switch (operation) {
                case "replay", "prime-dlq" -> send(runId, nameserver, options);
                case "queue-status" -> query(runId, nameserver, options);
                case "consume-dlq" -> consumeDeadLetter(runId, nameserver, options);
                default -> throw new IllegalArgumentException("unsupported operation");
            }
        } catch (Exception exception) {
            String detail = exception.getMessage() == null ? "" : exception.getMessage()
                    .replaceAll("[\\r\\n\\t]+", " ");
            if (detail.length() > 300) {
                detail = detail.substring(0, 300);
            }
            System.err.println("Comment recovery probe failed: " + exception.getClass().getSimpleName()
                    + (detail.isBlank() ? "" : ": " + detail));
            System.exit(1);
        }
    }

    private static void send(String runId, String nameserver, Map<String, String> options) throws Exception {
        String topic = required(options, "topic");
        String eventKey = required(options, "event-key");
        byte[] payload = options.containsKey("payload-stdin")
                ? java.util.Base64.getDecoder().decode(new String(System.in.readAllBytes(), StandardCharsets.UTF_8).trim())
                : java.util.Base64.getDecoder().decode(required(options, "payload-base64"));
        String tag = options.getOrDefault("tag", "");

        DefaultMQProducer producer = newProducer(runId, nameserver);
        try {
            producer.start();
            Message message = new Message(topic, tag, payload);
            message.setKeys(eventKey);
            SendResult result = producer.send(message, Duration.ofSeconds(10).toMillis());
            System.out.printf("operation=%s runId=%s topic=%s eventKeySha256=%s sendStatus=%s%n",
                    options.get("operation"), runId, topic, sha256(eventKey.getBytes(StandardCharsets.UTF_8)),
                    result.getSendStatus());
        } finally {
            producer.shutdown();
        }
    }

    private static void query(String runId, String nameserver, Map<String, String> options) throws Exception {
        String consumerGroup = required(options, "consumer-group");
        String eventKey = required(options, "event-key");
        long endTime = System.currentTimeMillis() + Duration.ofSeconds(5).toMillis();
        long beginTime = endTime - Duration.ofHours(4).toMillis();

        DefaultMQProducer producer = newProducer(runId, nameserver);
        try {
            producer.start();
            int retryCount = queryCount(producer, "%RETRY%" + consumerGroup, eventKey, beginTime, endTime);
            int deadLetterCount = queryCount(producer, "%DLQ%" + consumerGroup, eventKey, beginTime, endTime);
            System.out.printf("operation=queue-status runId=%s eventKeySha256=%s retryMatches=%d dlqMatches=%d%n",
                    runId, sha256(eventKey.getBytes(StandardCharsets.UTF_8)), retryCount, deadLetterCount);
        } finally {
            producer.shutdown();
        }
    }

    private static void consumeDeadLetter(String runId, String nameserver, Map<String, String> options) throws Exception {
        String consumerGroup = required(options, "consumer-group");
        String eventKey = required(options, "event-key");
        String topic = "%DLQ%" + consumerGroup;
        AtomicInteger acknowledged = new AtomicInteger();
        AtomicInteger matching = new AtomicInteger();
        CountDownLatch received = new CountDownLatch(1);
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(
                "acceptance_dlq_recovery_" + runId.replace('-', '_'));
        consumer.setNamesrvAddr(nameserver);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe(topic, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            for (var message : messages) {
                acknowledged.incrementAndGet();
                if (message.getKeys() != null && message.getKeys().contains(eventKey)) {
                    matching.incrementAndGet();
                    received.countDown();
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        try {
            consumer.start();
            boolean found = received.await(30, TimeUnit.SECONDS);
            if (!found || matching.get() == 0) {
                throw new IllegalStateException("matching dead-letter message was not received");
            }
            System.out.printf("operation=consume-dlq runId=%s eventKeySha256=%s acknowledged=%d matching=%d%n",
                    runId, sha256(eventKey.getBytes(StandardCharsets.UTF_8)), acknowledged.get(), matching.get());
        } finally {
            consumer.shutdown();
        }
    }

    private static int queryCount(DefaultMQProducer producer, String topic, String key,
                                  long beginTime, long endTime) throws Exception {
        try {
            QueryResult result = producer.queryMessage(topic, key, 32, beginTime, endTime);
            return result == null || result.getMessageList() == null ? 0 : result.getMessageList().size();
        } catch (Exception exception) {
            String message = exception.getMessage();
            String normalized = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("no route info") || normalized.contains("not matched route info")
                    || normalized.contains("topic_not_exist")
                    || normalized.contains("no topic route") || normalized.contains("code: 208")
                    || normalized.contains("no message")) {
                return 0;
            }
            throw exception;
        }
    }

    private static DefaultMQProducer newProducer(String runId, String nameserver) {
        DefaultMQProducer producer = new DefaultMQProducer("acceptance_probe_" + runId.replace('-', '_'));
        producer.setNamesrvAddr(nameserver);
        producer.setInstanceName("probe-" + UUID.randomUUID());
        producer.setSendMsgTimeout(10_000);
        return producer;
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("arguments must be --name value pairs");
            }
            result.put(arg.substring(2), args[++i]);
        }
        return result;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + name);
        }
        return value;
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
