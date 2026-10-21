package com.quanxiaoha.xiaohashu.data.align.job;

import com.quanxiaoha.xiaohashu.data.align.domain.mapper.CountAuditMapper;
import com.quanxiaoha.xiaohashu.data.align.domain.mapper.CountAuditMarker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reconciles entities tracked by the daily marker tables against authoritative sources. */
@Service
@Slf4j
public class CountReconciliationAuditService {

    private final CountAuditMapper mapper;
    private final CountReconciliationRepairService repairService;
    private final int batchSize;

    public CountReconciliationAuditService(CountAuditMapper mapper,
                                           CountReconciliationRepairService repairService,
                                           @Value("${data-align.audit.batch-size:1000}") int batchSize) {
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be positive");
        this.mapper = mapper;
        this.repairService = repairService;
        this.batchSize = batchSize;
    }

    public AuditSummary audit(CountAuditMetric metric, int executorShardIndex, int executorShardTotal) {
        if (executorShardTotal <= 0 || executorShardIndex < 0 || executorShardIndex >= executorShardTotal) {
            throw new IllegalArgumentException("Invalid XXL-JOB shard coordinates");
        }

        List<String> tableNames = mapper.selectMarkerTables(metric.getTablePrefix());
        return auditTables(metric, tableNames, executorShardIndex, executorShardTotal);
    }

    /** Audits one validated marker table before the retention job considers dropping it. */
    public AuditSummary auditTable(CountAuditMetric metric, String tableName) {
        parsePhysicalShard(metric, tableName);
        return auditTables(metric, List.of(tableName), 0, 1);
    }

    private AuditSummary auditTables(CountAuditMetric metric, List<String> tableNames,
                                     int executorShardIndex, int executorShardTotal) {
        long scanned = 0;
        long mismatches = 0;
        long repaired = 0;
        int selectedTables = 0;

        for (String tableName : tableNames == null ? List.<String>of() : tableNames) {
            int physicalShard = parsePhysicalShard(metric, tableName);
            if (Math.floorMod(physicalShard, executorShardTotal) != executorShardIndex) continue;
            selectedTables++;

            long lastRowId = 0;
            while (true) {
                List<CountAuditMarker> markers = mapper.selectBatch(
                        tableName, metric.getEntityColumn(), lastRowId, batchSize);
                if (markers == null || markers.isEmpty()) break;

                for (CountAuditMarker marker : markers) {
                    if (marker.getRowId() <= lastRowId) {
                        throw new IllegalStateException("Marker row cursor did not advance for " + tableName);
                    }
                    lastRowId = marker.getRowId();
                    long sourceCount = requireCount(mapper.selectSourceCount(metric.name(), marker.getEntityId()),
                            "source", metric, marker.getEntityId());
                    long storedCount = requireCount(mapper.selectStoredCount(metric.name(), marker.getEntityId()),
                            "stored", metric, marker.getEntityId());
                    scanned++;
                    if (sourceCount != storedCount) {
                        mismatches++;
                        CountReconciliationRepairService.RepairResult result =
                                repairService.repair(metric, marker.getEntityId());
                        if (result.repaired()) repaired++;
                        log.warn("Count audit mismatch metric={} table={} entityId={} source={} stored={}",
                                metric, tableName, marker.getEntityId(), sourceCount, storedCount);
                    }
                }
            }
        }

        return new AuditSummary(selectedTables, scanned, mismatches, repaired);
    }

    private int parsePhysicalShard(CountAuditMetric metric, String tableName) {
        if (tableName == null || !tableName.startsWith(metric.getTablePrefix())) {
            throw new IllegalStateException("Unexpected marker table name: " + tableName);
        }
        String suffix = tableName.substring(metric.getTablePrefix().length());
        Matcher matcher = Pattern.compile("\\d{8}_(\\d+)").matcher(suffix);
        if (!matcher.matches()) {
            throw new IllegalStateException("Unexpected marker table name: " + tableName);
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Physical shard index is too large in " + tableName, e);
        }
    }

    private long requireCount(Long count, String kind, CountAuditMetric metric, long entityId) {
        if (count == null || count < 0) {
            throw new IllegalStateException("Missing or invalid " + kind + " count for " + metric + ":" + entityId);
        }
        return count;
    }

    public record AuditSummary(int physicalTables, long scannedEntities, long mismatches, long repaired) {
        public AuditSummary(int physicalTables, long scannedEntities, long mismatches) {
            this(physicalTables, scannedEntities, mismatches, 0);
        }
    }
}
