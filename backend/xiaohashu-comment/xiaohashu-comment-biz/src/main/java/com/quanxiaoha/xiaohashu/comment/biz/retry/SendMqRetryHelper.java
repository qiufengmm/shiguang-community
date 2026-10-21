package com.quanxiaoha.xiaohashu.comment.biz.retry;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Retry helper for publishing MQ messages. */
@Component
@Slf4j
public class SendMqRetryHelper {

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Resource
    private RetryTemplate retryTemplate;

    @Resource(name = "taskExecutor")
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;

    /** Send a message asynchronously and retry failures on a worker thread. */
    public void asyncSend(String topic, String body) {
        log.info("==> 开始异步发送 MQ, Topic: {}, Body: {}", topic, body);

        Message<String> message = MessageBuilder.withPayload(body).build();
        rocketMQTemplate.asyncSend(topic, message, new SendCallback() {
            @Override
            public void onSuccess(SendResult sendResult) {
                log.info("==> 【评论发布】MQ 发送成功，SendResult: {}", sendResult);
            }

            @Override
            public void onException(Throwable throwable) {
                log.error("==> 【评论发布】MQ 发送异常: ", throwable);
                handleRetry(topic, message);
            }
        });
    }

    private void handleRetry(String topic, Message<String> message) {
        threadPoolTaskExecutor.submit(() -> {
            try {
                retryTemplate.execute((RetryCallback<Void, RuntimeException>) context -> {
                    log.info("==> 开始重试 MQ 发送, 当前重试次数: {}, 时间: {}",
                            context.getRetryCount() + 1, LocalDateTime.now());
                    rocketMQTemplate.syncSend(topic, message);
                    return null;
                });
            } catch (Exception e) {
                fallback(e, topic, message.getPayload());
            }
        });
    }

    /** Persist the failed message for scheduled compensation; persistence is left as the tutorial TODO. */
    private void fallback(Exception e, String topic, String bodyJson) {
        log.error("==> 多次发送失败, 进入兜底方案, Topic: {}, bodyJson: {}", topic, bodyJson, e);

        // TODO: persist the failed message and resend it from a scheduled compensation job.
    }
}
