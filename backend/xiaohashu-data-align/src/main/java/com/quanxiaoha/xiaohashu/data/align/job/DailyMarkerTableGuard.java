package com.quanxiaoha.xiaohashu.data.align.job;

import com.quanxiaoha.xiaohashu.data.align.constant.TableConstants;
import com.quanxiaoha.xiaohashu.data.align.domain.mapper.CreateTableMapper;
import com.quanxiaoha.xiaohashu.data.align.domain.mapper.TableExistenceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 日增量标记表启动自检。
 * <p>
 * 标记表由 {@link CreateTableXxlJob} 在每天 23:00 预创建「次日」的表。只要那一晚
 * 调度没生效（栈在发版、机器重启、任务被停用），当天的表就没人建，随之而来的是：
 * 标记写入持续报 {@code Table ... doesn't exist}，凌晨对账扫不到任何实体，
 * 兜底链路静默失效 —— 而且没有任何地方会报警。
 * <p>
 * 这里在启动时补一次检查：缺表直接补建，并把缺失清单打到日志里，让问题可见。
 */
@Component
@Slf4j
public class DailyMarkerTableGuard {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** information_schema 单次 IN 查询的批量上限 */
    private static final int QUERY_BATCH_SIZE = 100;

    private final CreateTableMapper createTableMapper;
    private final TableExistenceMapper tableExistenceMapper;
    private final int tableShards;

    public DailyMarkerTableGuard(CreateTableMapper createTableMapper,
                                 TableExistenceMapper tableExistenceMapper,
                                 @Value("${table.shards}") int tableShards) {
        if (tableShards <= 0) {
            throw new IllegalArgumentException("table.shards must be positive");
        }
        this.createTableMapper = createTableMapper;
        this.tableExistenceMapper = tableExistenceMapper;
        this.tableShards = tableShards;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verifyOnStartup() {
        LocalDate today = LocalDate.now();
        // 今天必须齐；明天顺手补一次，避免当晚调度再漏跑一次又断一天
        for (LocalDate date : List.of(today, today.plusDays(1))) {
            try {
                checkAndRepair(date);
            } catch (Exception e) {
                log.error("## 日增量标记表自检失败，date={}，兜底链路可能不可用", date, e);
            }
        }
    }

    /**
     * 检查指定日期的标记表，缺失则补建。
     *
     * @return 实际补建的表数量，0 表示本来就齐全
     */
    public int checkAndRepair(LocalDate date) {
        String dateSuffix = date.format(DATE_FORMAT);
        List<String> expected = expectedTableNames(dateSuffix);
        List<String> missing = findMissing(expected);

        if (missing.isEmpty()) {
            log.info("## 日增量标记表自检通过，date={}，{} 张表齐全", dateSuffix, expected.size());
            return 0;
        }

        log.warn("## 日增量标记表自检发现缺失，date={}，缺失 {} 张，开始补建。缺失清单: {}",
                dateSuffix, missing.size(), missing);
        Set<String> missingSet = new HashSet<>(missing);
        for (int shard = 0; shard < tableShards; shard++) {
            String suffix = TableConstants.buildTableNameSuffix(dateSuffix, shard);
            for (CountAuditMetric metric : CountAuditMetric.values()) {
                if (!missingSet.contains(metric.getTablePrefix() + suffix)) {
                    continue;
                }
                createTable(metric, suffix);
            }
        }
        log.warn("## 日增量标记表补建完成，date={}，补建 {} 张。"
                        + "请排查 createTableJobHandler 前一天为何没有执行，否则次日仍会重演",
                dateSuffix, missing.size());
        return missing.size();
    }

    private void createTable(CountAuditMetric metric, String suffix) {
        switch (metric) {
            case FOLLOWING -> createTableMapper.createDataAlignFollowingCountTempTable(suffix);
            case FANS -> createTableMapper.createDataAlignFansCountTempTable(suffix);
            case NOTE_COLLECT -> createTableMapper.createDataAlignNoteCollectCountTempTable(suffix);
            case USER_COLLECT -> createTableMapper.createDataAlignUserCollectCountTempTable(suffix);
            case USER_LIKE -> createTableMapper.createDataAlignUserLikeCountTempTable(suffix);
            case NOTE_LIKE -> createTableMapper.createDataAlignNoteLikeCountTempTable(suffix);
            case NOTE_PUBLISH -> createTableMapper.createDataAlignNotePublishCountTempTable(suffix);
            // 新增维度却忘了在这里补分支时，直接炸出来，不要静默跳过
            default -> throw new IllegalStateException("Unhandled count metric: " + metric);
        }
    }

    /**
     * 该日期应有的全部标记表名：计数维度 × 物理分片。
     */
    List<String> expectedTableNames(String dateSuffix) {
        CountAuditMetric[] metrics = CountAuditMetric.values();
        List<String> names = new ArrayList<>(metrics.length * tableShards);
        for (CountAuditMetric metric : metrics) {
            for (int shard = 0; shard < tableShards; shard++) {
                names.add(metric.getTablePrefix() + TableConstants.buildTableNameSuffix(dateSuffix, shard));
            }
        }
        return names;
    }

    private List<String> findMissing(List<String> expected) {
        List<String> existing = new ArrayList<>(expected.size());
        for (int from = 0; from < expected.size(); from += QUERY_BATCH_SIZE) {
            List<String> slice = expected.subList(from, Math.min(from + QUERY_BATCH_SIZE, expected.size()));
            List<String> found = tableExistenceMapper.selectExistingTableNames(slice);
            if (found != null) {
                existing.addAll(found);
            }
        }
        List<String> missing = new ArrayList<>(expected);
        missing.removeAll(existing);
        return missing;
    }
}
