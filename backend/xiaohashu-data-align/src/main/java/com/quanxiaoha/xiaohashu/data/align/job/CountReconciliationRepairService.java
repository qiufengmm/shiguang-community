package com.quanxiaoha.xiaohashu.data.align.job;

import com.quanxiaoha.xiaohashu.data.align.domain.mapper.CountAuditMapper;
import com.quanxiaoha.xiaohashu.data.align.rpc.CountNotePublishReconciliationRpcService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Repairs one marked counter while holding its MySQL row lock. */
@Service
public class CountReconciliationRepairService {

    private final CountAuditMapper mapper;

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    @Resource
    private CountNotePublishReconciliationRpcService notePublishReconciliationRpcService;

    public CountReconciliationRepairService(CountAuditMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public RepairResult repair(CountAuditMetric metric, long entityId) {
        if (metric == null) throw new IllegalArgumentException("metric is required");
        if (entityId <= 0) throw new IllegalArgumentException("entityId must be positive");

        if (metric == CountAuditMetric.NOTE_PUBLISH) {
            long storedBefore = requireCount(mapper.selectStoredCount(metric.name(), entityId),
                    "stored", metric, entityId);
            if (notePublishReconciliationRpcService == null
                    || !notePublishReconciliationRpcService.reconcile(entityId)) {
                throw new IllegalStateException("Count service did not reconcile note publish count for " + entityId);
            }
            long sourceCount = requireCount(mapper.selectStoredCount(metric.name(), entityId),
                    "reconciled", metric, entityId);
            return new RepairResult(sourceCount, storedBefore, sourceCount != storedBefore);
        }

        String metricName = metric.name();
        mapper.ensureCounterRow(metricName, entityId);
        if (mapper.lockCounterRow(metricName, entityId) == null) {
            throw new IllegalStateException("Could not lock count row for " + metric + ":" + entityId);
        }

        long sourceCount = requireCount(mapper.selectSourceCount(metricName, entityId), "source", metric, entityId);
        long storedCount = requireCount(mapper.selectStoredCount(metricName, entityId), "stored", metric, entityId);
        boolean repaired = sourceCount != storedCount;
        if (repaired && mapper.updateStoredCount(metricName, entityId, sourceCount) != 1) {
            throw new IllegalStateException("Could not update count row for " + metric + ":" + entityId);
        }
        if (repaired) invalidateCacheAfterCommit(metric, entityId);
        return new RepairResult(sourceCount, storedCount, repaired);
    }

    private void invalidateCacheAfterCommit(CountAuditMetric metric, long entityId) {
        String key = metric == CountAuditMetric.NOTE_LIKE || metric == CountAuditMetric.NOTE_COLLECT
                ? "count:note:" + entityId : "count:user:" + entityId;
        Runnable invalidate = () -> redisTemplate.delete(key);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidate.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidate.run();
            }
        });
    }

    private long requireCount(Long count, String kind, CountAuditMetric metric, long entityId) {
        if (count == null || count < 0) {
            throw new IllegalStateException("Missing or invalid " + kind + " count for " + metric + ":" + entityId);
        }
        return count;
    }

    public record RepairResult(long sourceCount, long previousCount, boolean repaired) { }
}
