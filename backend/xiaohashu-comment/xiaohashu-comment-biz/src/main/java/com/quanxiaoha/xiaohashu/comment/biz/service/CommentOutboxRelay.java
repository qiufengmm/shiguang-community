package com.quanxiaoha.xiaohashu.comment.biz.service;

import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentOutboxDO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@Slf4j
public class CommentOutboxRelay {
    @Resource
    private CommentOutboxService commentOutboxService;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Scheduled(fixedDelayString = "${comment.delivery.outbox-relay-delay-ms:1000}")
    public void relayDueMessages() {
        relayBatch();
    }

    public int relayBatch() {
        String owner = UUID.randomUUID().toString();
        List<CommentOutboxDO> batch = commentOutboxService.claimBatch(owner);
        for (CommentOutboxDO record : batch) {
            try {
                Message<String> message = MessageBuilder.withPayload(record.getPayload())
                        .setHeader("KEYS", record.getEventKey())
                        .build();
                SendResult result = rocketMQTemplate.syncSend(record.getTopic(), message);
                if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                    throw new IllegalStateException("RocketMQ did not confirm SEND_OK");
                }
                commentOutboxService.markSent(record);
            } catch (Exception e) {
                try {
                    commentOutboxService.scheduleRetry(record, e);
                } catch (Exception retryUpdateError) {
                    log.error("Comment outbox retry metadata update failed, eventKey={}", record.getEventKey(), retryUpdateError);
                }
                log.error("Comment outbox delivery failed, eventKey={}, topic={}", record.getEventKey(), record.getTopic(), e);
            }
        }
        return batch.size();
    }
}
