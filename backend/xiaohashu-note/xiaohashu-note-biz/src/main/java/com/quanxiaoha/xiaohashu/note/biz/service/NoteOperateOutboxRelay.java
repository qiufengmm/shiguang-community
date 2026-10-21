package com.quanxiaoha.xiaohashu.note.biz.service;

import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteOperateOutboxDO;
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
public class NoteOperateOutboxRelay {

    @Resource
    private NoteOperateOutboxService outboxService;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Scheduled(fixedDelayString = "${note.operate.outbox-relay-delay-ms:1000}")
    public void relayDueMessages() {
        relayBatch();
    }

    public int relayBatch() {
        String owner = UUID.randomUUID().toString();
        List<NoteOperateOutboxDO> batch = outboxService.claimBatch(owner);
        for (NoteOperateOutboxDO record : batch) {
            try {
                Message<String> message = MessageBuilder.withPayload(record.getPayload())
                        .setHeader("KEYS", record.getEventId())
                        .build();
                SendResult result = rocketMQTemplate.syncSend(record.getTopic(), message);
                if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                    throw new IllegalStateException("RocketMQ did not confirm SEND_OK");
                }
                outboxService.markSent(record);
            } catch (Exception e) {
                try {
                    outboxService.scheduleRetry(record, e);
                } catch (Exception retryUpdateError) {
                    log.error("Note operation outbox retry metadata update failed, eventId={}",
                            record.getEventId(), retryUpdateError);
                }
                log.error("Note operation outbox delivery failed, eventId={}, topic={}",
                        record.getEventId(), record.getTopic(), e);
            }
        }
        return batch.size();
    }
}
