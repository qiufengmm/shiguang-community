package com.quanxiaoha.xiaohashu.comment.biz.service;

import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentInboxMapper;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentOutboxMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.function.IntSupplier;

/** Removes old comment text from terminal delivery records while retaining deduplication keys. */
@Component
@Slf4j
public class CommentDeliveryPayloadRetentionScheduler {
    private static final int BATCH_SIZE = 500;
    private static final int MAX_BATCHES_PER_RUN = 20;

    @Resource
    private CommentInboxMapper commentInboxMapper;

    @Resource
    private CommentOutboxMapper commentOutboxMapper;

    @Value("${comment.delivery.payload-retention-days:30}")
    private int payloadRetentionDays = 30;

    @Scheduled(initialDelayString = "${comment.delivery.payload-retention-initial-delay-ms:60000}",
            fixedDelayString = "${comment.delivery.payload-retention-delay-ms:3600000}")
    public void scrubOldTerminalPayloads() {
        if (payloadRetentionDays < 1) {
            throw new IllegalStateException("comment delivery payload retention must be at least one day");
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(payloadRetentionDays);
        int inboxCount = scrubInbox(cutoff);
        int outboxCount = scrubOutbox(cutoff);
        if (inboxCount > 0 || outboxCount > 0) {
            log.info("Scrubbed old terminal comment delivery payloads, inbox={}, outbox={}", inboxCount, outboxCount);
        }
    }

    private int scrubInbox(LocalDateTime cutoff) {
        return scrubInBatches(() -> commentInboxMapper.scrubAppliedPayloadsBefore(cutoff, BATCH_SIZE));
    }

    private int scrubOutbox(LocalDateTime cutoff) {
        return scrubInBatches(() -> commentOutboxMapper.scrubSentPayloadsBefore(cutoff, BATCH_SIZE));
    }

    private static int scrubInBatches(IntSupplier updateBatch) {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int updated = updateBatch.getAsInt();
            total += updated;
            if (updated < BATCH_SIZE) {
                break;
            }
        }
        return total;
    }
}
