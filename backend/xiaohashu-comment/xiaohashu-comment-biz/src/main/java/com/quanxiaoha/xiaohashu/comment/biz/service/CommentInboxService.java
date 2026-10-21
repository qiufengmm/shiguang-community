package com.quanxiaoha.xiaohashu.comment.biz.service;

import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentInboxDO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentInboxMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

@Service
public class CommentInboxService {
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final long LEASE_SECONDS = 60;
    private static final long MAX_RETRY_DELAY_SECONDS = 15 * 60;

    @Resource
    private CommentInboxMapper commentInboxMapper;

    @Resource
    private TransactionTemplate transactionTemplate;

    /** Persist before a RocketMQ listener acknowledges the delivery. */
    public void receive(String eventKey, String eventType, String payload) {
        Objects.requireNonNull(eventKey, "eventKey");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payload, "payload");
        int inserted = commentInboxMapper.insertIfAbsent(CommentInboxDO.builder()
                .eventKey(eventKey)
                .eventType(eventType)
                .payload(payload)
                .build());
        if (inserted == 0) {
            CommentInboxDO existing = commentInboxMapper.selectByEventKey(eventKey);
            boolean alreadyAppliedPayloadWasScrubbed = existing != null
                    && "APPLIED".equals(existing.getStatus()) && StringUtils.isEmpty(existing.getPayload());
            if (existing == null || !eventType.equals(existing.getEventType())
                    || (!payload.equals(existing.getPayload()) && !alreadyAppliedPayloadWasScrubbed)) {
                throw new IllegalStateException("event key already belongs to another payload");
            }
        }
    }

    public List<CommentInboxDO> claimBatch(String owner) {
        LocalDateTime now = LocalDateTime.now();
        return transactionTemplate.execute(status -> {
            List<CommentInboxDO> due = commentInboxMapper.selectClaimable(now, DEFAULT_BATCH_SIZE);
            for (CommentInboxDO record : due) {
                int leased = commentInboxMapper.acquireLease(record.getId(), owner,
                        now.plusSeconds(LEASE_SECONDS), now);
                if (leased != 1) {
                    throw new IllegalStateException("failed to acquire locked inbox row");
                }
                record.setStatus("PROCESSING");
                record.setAttemptCount(record.getAttemptCount() + 1);
                record.setLeaseOwner(owner);
                record.setLeaseUntil(now.plusSeconds(LEASE_SECONDS));
                record.setLeaseVersion(record.getLeaseVersion() + 1);
            }
            return due;
        });
    }

    /** Call in the same MySQL transaction as the idempotent comment and follow-up writes. */
    public void markApplied(CommentInboxDO record) {
        int updated = commentInboxMapper.markApplied(record.getId(), record.getLeaseOwner(),
                record.getLeaseVersion(), LocalDateTime.now());
        if (updated != 1) {
            throw new IllegalStateException("inbox lease was lost before APPLIED update");
        }
    }

    public void scheduleRetry(CommentInboxDO record, Throwable failure) {
        long delaySeconds = retryDelaySeconds(record.getAttemptCount());
        LocalDateTime now = LocalDateTime.now();
        String error = safeErrorSummary(failure);
        int updated = commentInboxMapper.scheduleRetry(record.getId(), record.getLeaseOwner(),
                record.getLeaseVersion(), now.plusSeconds(delaySeconds), error, now);
        if (updated != 1) {
            throw new IllegalStateException("inbox lease was lost before retry update");
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
