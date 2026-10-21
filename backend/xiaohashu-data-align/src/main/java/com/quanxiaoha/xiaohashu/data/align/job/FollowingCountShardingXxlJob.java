package com.quanxiaoha.xiaohashu.data.align.job;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Daily reconciliation for following counts. */
@Component
@Slf4j
public class FollowingCountShardingXxlJob {

    private final CountReconciliationAuditService auditService;

    public FollowingCountShardingXxlJob(CountReconciliationAuditService auditService) {
        this.auditService = auditService;
    }

    @XxlJob("followingCountShardingJobHandler")
    public void followingCountShardingJobHandler() {
        int shardIndex = XxlJobHelper.getShardIndex();
        int shardTotal = XxlJobHelper.getShardTotal();
        CountReconciliationAuditService.AuditSummary summary = auditService.audit(
                CountAuditMetric.FOLLOWING, shardIndex, shardTotal);
        String message = "Following count reconciliation finished: tables={}, entities={}, mismatches={}, repaired={}";
        XxlJobHelper.log(message, summary.physicalTables(), summary.scannedEntities(), summary.mismatches(), summary.repaired());
        log.info(message, summary.physicalTables(), summary.scannedEntities(), summary.mismatches(), summary.repaired());
    }
}
