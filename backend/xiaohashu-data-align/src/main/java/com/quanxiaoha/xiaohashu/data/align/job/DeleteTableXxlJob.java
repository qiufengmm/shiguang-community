package com.quanxiaoha.xiaohashu.data.align.job;

import com.quanxiaoha.xiaohashu.data.align.domain.mapper.CountAuditMapper;
import com.quanxiaoha.xiaohashu.data.align.domain.mapper.CountConsumeLogCleanMapper;
import com.quanxiaoha.xiaohashu.data.align.domain.mapper.DeleteTableMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Audit expired marker tables once more, then drop only tables with no remaining mismatch. */
@Component
@Slf4j
public class DeleteTableXxlJob {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * 幂等日志的保留天数。必须长于 RocketMQ 的消息保留时长（默认 72 小时），
     * 否则过期消息被重放时会失去幂等保护。
     */
    private static final int CONSUME_LOG_RETENTION_DAYS = 7;

    /** 单次删除上限，避免一次清理锁表太久。 */
    private static final int CONSUME_LOG_DELETE_LIMIT = 20000;

    private final CountAuditMapper countAuditMapper;
    private final CountReconciliationAuditService auditService;
    private final DeleteTableMapper deleteTableMapper;
    private final CountConsumeLogCleanMapper countConsumeLogCleanMapper;

    public DeleteTableXxlJob(CountAuditMapper countAuditMapper,
                             CountReconciliationAuditService auditService,
                             DeleteTableMapper deleteTableMapper,
                             CountConsumeLogCleanMapper countConsumeLogCleanMapper) {
        this.countAuditMapper = countAuditMapper;
        this.auditService = auditService;
        this.deleteTableMapper = deleteTableMapper;
        this.countConsumeLogCleanMapper = countConsumeLogCleanMapper;
    }

    @XxlJob("deleteTableJobHandler")
    public void deleteTableJobHandler() {
        LocalDate cutoff = LocalDate.now().minusMonths(1);
        int dropped = 0;
        int retained = 0;

        for (CountAuditMetric metric : CountAuditMetric.values()) {
            List<String> tables = countAuditMapper.selectMarkerTables(metric.getTablePrefix());
            for (String tableName : tables) {
                String suffix = suffixFor(metric, tableName);
                LocalDate tableDate = parseDate(suffix, tableName);
                if (!tableDate.isBefore(cutoff)) continue;

                CountReconciliationAuditService.AuditSummary summary = auditService.auditTable(metric, tableName);
                if (summary.mismatches() > summary.repaired()) {
                    retained++;
                    logToJob("Retaining expired marker table with unresolved count mismatches: {}, entities={}, mismatches={}, repaired={}",
                            tableName, summary.scannedEntities(), summary.mismatches(), summary.repaired());
                    continue;
                }

                drop(metric, suffix);
                dropped++;
                logToJob("Dropped audited expired marker table: {}, entities={}, repaired={}",
                        tableName, summary.scannedEntities(), summary.repaired());
            }
        }

        logToJob("Marker cleanup finished: auditedExpiredTables={}, retainedTables={}",
                dropped, retained);
        log.info("Marker cleanup finished: auditedExpiredTables={}, retainedTables={}", dropped, retained);

        cleanExpiredConsumeLog();
    }

    /**
     * 顺手清理计数二级消费者的幂等日志，避免 {@code t_count_consume_log} 无限增长。
     * <p>
     * 只作为“清理”用途，即便这次没删干净（有 LIMIT），下一次任务还会继续删。
     */
    private void cleanExpiredConsumeLog() {
        int deleted = countConsumeLogCleanMapper.deleteExpired(
                CONSUME_LOG_RETENTION_DAYS, CONSUME_LOG_DELETE_LIMIT);
        logToJob("Count consume log cleanup finished: retentionDays={}, deleted={}",
                CONSUME_LOG_RETENTION_DAYS, deleted);
        log.info("Count consume log cleanup finished: retentionDays={}, deleted={}",
                CONSUME_LOG_RETENTION_DAYS, deleted);
    }

    protected void logToJob(String message, Object... arguments) {
        XxlJobHelper.log(message, arguments);
    }

    private String suffixFor(CountAuditMetric metric, String tableName) {
        if (tableName == null || !tableName.startsWith(metric.getTablePrefix())) {
            throw new IllegalStateException("Unexpected marker table name: " + tableName);
        }
        String suffix = tableName.substring(metric.getTablePrefix().length());
        if (!Pattern.matches("\\d{8}_\\d+", suffix)) {
            throw new IllegalStateException("Unexpected marker table suffix: " + suffix);
        }
        return suffix;
    }

    private LocalDate parseDate(String suffix, String tableName) {
        Matcher matcher = Pattern.compile("^(\\d{8})_\\d+$").matcher(suffix);
        if (!matcher.matches()) throw new IllegalStateException("Unexpected marker table name: " + tableName);
        try {
            return LocalDate.parse(matcher.group(1), DATE_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("Invalid date in marker table name: " + tableName, e);
        }
    }

    private void drop(CountAuditMetric metric, String suffix) {
        switch (metric) {
            case FOLLOWING -> deleteTableMapper.deleteDataAlignFollowingCountTempTable(suffix);
            case FANS -> deleteTableMapper.deleteDataAlignFansCountTempTable(suffix);
            case NOTE_COLLECT -> deleteTableMapper.deleteDataAlignNoteCollectCountTempTable(suffix);
            case USER_COLLECT -> deleteTableMapper.deleteDataAlignUserCollectCountTempTable(suffix);
            case USER_LIKE -> deleteTableMapper.deleteDataAlignUserLikeCountTempTable(suffix);
            case NOTE_LIKE -> deleteTableMapper.deleteDataAlignNoteLikeCountTempTable(suffix);
            case NOTE_PUBLISH -> deleteTableMapper.deleteDataAlignNotePublishCountTempTable(suffix);
        }
    }
}
