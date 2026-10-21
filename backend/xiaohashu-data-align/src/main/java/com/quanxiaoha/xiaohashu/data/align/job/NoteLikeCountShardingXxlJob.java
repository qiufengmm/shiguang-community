package com.quanxiaoha.xiaohashu.data.align.job;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Read-only daily audit for note like counts. */
@Component
@Slf4j
public class NoteLikeCountShardingXxlJob {

    private final CountReconciliationAuditService auditService;

    public NoteLikeCountShardingXxlJob(CountReconciliationAuditService auditService) {
        this.auditService = auditService;
    }

    @XxlJob("noteLikeCountShardingJobHandler")
    public void noteLikeCountShardingJobHandler() {
        int shardIndex = XxlJobHelper.getShardIndex();
        int shardTotal = XxlJobHelper.getShardTotal();
        CountReconciliationAuditService.AuditSummary summary = auditService.audit(
                CountAuditMetric.NOTE_LIKE, shardIndex, shardTotal);
        String message = "Note like count audit finished: tables={}, entities={}, mismatches={}";
        XxlJobHelper.log(message, summary.physicalTables(), summary.scannedEntities(), summary.mismatches());
        log.info(message, summary.physicalTables(), summary.scannedEntities(), summary.mismatches());
    }
}
