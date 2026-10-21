package com.quanxiaoha.xiaohashu.comment.biz.service;

import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentOutboxDO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentOutboxMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

@Service
public class CommentOutboxService {
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final long LEASE_SECONDS = 30;
    private static final long MAX_RETRY_DELAY_SECONDS = 15 * 60;

    @Resource
    private CommentOutboxMapper commentOutboxMapper;

    @Resource
    private TransactionTemplate transactionTemplate;

    /** Must be called inside the business transaction that accepts the event. */
    public void enqueue(String eventKey, String topic, String payload) {
        Objects.requireNonNull(eventKey, "eventKey");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(payload, "payload");
        int inserted = commentOutboxMapper.insertIfAbsent(CommentOutboxDO.builder()
                .eventKey(eventKey)
                .topic(topic)
                .payload(payload)
                .build());
        if (inserted == 0) {
            CommentOutboxDO existing = commentOutboxMapper.selectByEventKey(eventKey);
            boolean sentPayloadWasScrubbed = existing != null
                    && "SENT".equals(existing.getStatus()) && StringUtils.isEmpty(existing.getPayload());
            if (existing == null || !topic.equals(existing.getTopic())
                    || (!payload.equals(existing.getPayload()) && !sentPayloadWasScrubbed)) {
                throw new IllegalStateException("event key already belongs to another payload");
            }
        }
    }

    public boolean existsByEventKey(String eventKey) {
        return commentOutboxMapper.existsByEventKey(eventKey) > 0;
    }

    public List<CommentOutboxDO> claimBatch(String owner) {
        LocalDateTime now = LocalDateTime.now();
        return transactionTemplate.execute(status -> {
            List<CommentOutboxDO> due = commentOutboxMapper.selectClaimable(now, DEFAULT_BATCH_SIZE);
            for (CommentOutboxDO record : due) {
                int leased = commentOutboxMapper.acquireLease(record.getId(), owner,
                        now.plusSeconds(LEASE_SECONDS), now);
                if (leased != 1) {
                    throw new IllegalStateException("failed to acquire locked outbox row");
                }
                record.setStatus("SENDING");
                record.setAttemptCount(record.getAttemptCount() + 1);
                record.setLeaseOwner(owner);
                record.setLeaseUntil(now.plusSeconds(LEASE_SECONDS));
                record.setLeaseVersion(record.getLeaseVersion() + 1);
            }
            return due;
        });
    }

    public void markSent(CommentOutboxDO record) {
        int updated = commentOutboxMapper.markSent(record.getId(), record.getLeaseOwner(),
                record.getLeaseVersion(), LocalDateTime.now());
        if (updated != 1) {
            throw new IllegalStateException("outbox lease was lost before SENT update");
        }
    }

    public void scheduleRetry(CommentOutboxDO record, Throwable failure) {
        long delaySeconds = retryDelaySeconds(record.getAttemptCount());
        LocalDateTime now = LocalDateTime.now();
        String error = safeErrorSummary(failure);
        int updated = commentOutboxMapper.scheduleRetry(record.getId(), record.getLeaseOwner(),
                record.getLeaseVersion(), now.plusSeconds(delaySeconds), error, now);
        if (updated != 1) {
            throw new IllegalStateException("outbox lease was lost before retry update");
        }
    }

    static long retryDelaySeconds(int attempts) {
        long delay = 1;
        for (int i = 1; i < Math.max(attempts, 1) && delay < MAX_RETRY_DELAY_SECONDS; i++) {
            delay = Math.min(delay * 2, MAX_RETRY_DELAY_SECONDS);
        }
        return delay;
    }

    private static String safeErrorSummary(Throwable failure) {
        String summary = failure.getMessage();
        if (summary == null || summary.isBlank()) {
            summary = failure.getClass().getSimpleName();
        }
        return summary.length() > 1000 ? summary.substring(0, 1000) : summary;
    }
}
