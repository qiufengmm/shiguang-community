package com.quanxiaoha.xiaohashu.data.align.job;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Reconciliation handlers for count dimensions missing from the tutorial implementation. */
@Component
@Slf4j
public class SupplementalCountReconciliationXxlJobs {

    private final CountReconciliationAuditService auditService;

    public SupplementalCountReconciliationXxlJobs(CountReconciliationAuditService auditService) {
        this.auditService = auditService;
    }

    @XxlJob("fansCountShardingJobHandler")
    public void fansCountShardingJobHandler() {
        run(CountAuditMetric.FANS);
    }

    @XxlJob("noteCollectCountShardingJobHandler")
    public void noteCollectCountShardingJobHandler() {
        run(CountAuditMetric.NOTE_COLLECT);
    }

    @XxlJob("userCollectCountShardingJobHandler")
    public void userCollectCountShardingJobHandler() {
        run(CountAuditMetric.USER_COLLECT);
    }

    @XxlJob("userLikeCountShardingJobHandler")
    public void userLikeCountShardingJobHandler() {
        run(CountAuditMetric.USER_LIKE);
    }

    @XxlJob("notePublishCountShardingJobHandler")
    public void notePublishCountShardingJobHandler() {
        run(CountAuditMetric.NOTE_PUBLISH);
    }

    private void run(CountAuditMetric metric) {
        int shardIndex = XxlJobHelper.getShardIndex();
        int shardTotal = XxlJobHelper.getShardTotal();
        CountReconciliationAuditService.AuditSummary summary = auditService.audit(metric, shardIndex, shardTotal);
        String message = "{} count reconciliation finished: tables={}, entities={}, mismatches={}, repaired={}";
        XxlJobHelper.log(message, metric, summary.physicalTables(), summary.scannedEntities(), summary.mismatches(), summary.repaired());
        log.info(message, metric, summary.physicalTables(), summary.scannedEntities(), summary.mismatches(), summary.repaired());
    }
}
