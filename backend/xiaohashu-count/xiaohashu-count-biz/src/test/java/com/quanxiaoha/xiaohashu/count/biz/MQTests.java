package com.quanxiaoha.xiaohashu.count.biz;

import com.quanxiaoha.framework.common.util.JsonUtils;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "XIAOHASHU_TEST_ROCKETMQ", matches = "true")
class MQTests {

    private static final int MESSAGE_COUNT = 1620;
    private static final String TOPIC = "CodexReliabilityProbeTopic";

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Test
    void testBatchSendMQ() throws InterruptedException {
        CountDownLatch callbacks = new CountDownLatch(MESSAGE_COUNT);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        String testRunId = UUID.randomUUID().toString();

        for (int i = 0; i < MESSAGE_COUNT; i++) {
            Map<String, Object> comment = new HashMap<>();
            comment.put("commentId", ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L));
            comment.put("noteId", ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L));
            comment.put("content", "23.5-batch-" + testRunId + "-" + i);
            comment.put("imageUrl", "");
            comment.put("replyCommentId", null);
            comment.put("createTime", LocalDateTime.now());
            comment.put("creatorId", ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L));
            Message<String> message = MessageBuilder.withPayload(JsonUtils.toJsonString(comment)).build();
            rocketMQTemplate.asyncSend(TOPIC, message, new SendCallback() {
                @Override
                public void onSuccess(SendResult sendResult) {
                    callbacks.countDown();
                }

                @Override
                public void onException(Throwable throwable) {
                    firstFailure.compareAndSet(null, throwable);
                    callbacks.countDown();
                }
            });
        }

        assertThat(callbacks.await(90, TimeUnit.SECONDS)).isTrue();
        assertThat(firstFailure.get()).isNull();
    }
}
