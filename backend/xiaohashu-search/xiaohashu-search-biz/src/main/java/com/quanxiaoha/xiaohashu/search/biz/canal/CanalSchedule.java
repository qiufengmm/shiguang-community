package com.quanxiaoha.xiaohashu.search.biz.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.google.common.collect.Maps;
import com.quanxiaoha.xiaohashu.search.biz.service.DocumentIndexRefreshService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Consume MySQL binlog events and refresh Elasticsearch documents from current MySQL state. */
@Component
@Slf4j
public class CanalSchedule implements Runnable {
    private static final long RECONNECT_BACKOFF_MILLIS = 5_000;

    @Resource
    private CanalProperties canalProperties;
    @Resource
    private CanalClient canalClient;
    @Resource
    private DocumentIndexRefreshService documentIndexRefreshService;

    private long nextReconnectAttemptAt;

    @Override
    @Scheduled(fixedDelay = 100)
    public void run() {
        CanalConnector canalConnector = canalClient.getCurrentConnector();
        if (Objects.isNull(canalConnector)) {
            reconnectIfDue();
            return;
        }

        Message message;
        try {
            message = canalConnector.getWithoutAck(canalProperties.getBatchSize());
        } catch (Exception e) {
            invalidateAndScheduleReconnect(canalConnector, e);
            return;
        }

        long batchId = message.getId();
        if (batchId == -1 || message.getEntries().isEmpty()) {
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            try {
                processEntry(message.getEntries());
            } catch (Exception e) {
                log.error("消费 Canal 批次数据异常", e);
                rollbackOrReconnect(canalConnector, batchId, e);
                return;
            }
        }

        if (batchId != -1) {
            try {
                canalConnector.ack(batchId);
            } catch (Exception e) {
                invalidateAndScheduleReconnect(canalConnector, e);
            }
        }
    }

    private void rollbackOrReconnect(CanalConnector canalConnector, long batchId, Exception failure) {
        try {
            canalConnector.rollback(batchId);
        } catch (Exception rollbackError) {
            failure.addSuppressed(rollbackError);
            invalidateAndScheduleReconnect(canalConnector, failure);
        }
    }

    private void invalidateAndScheduleReconnect(CanalConnector canalConnector, Exception failure) {
        log.error("Canal connection failed; invalidating it and retrying after backoff", failure);
        canalClient.invalidateConnection(canalConnector);
        nextReconnectAttemptAt = System.currentTimeMillis() + RECONNECT_BACKOFF_MILLIS;
    }

    private void reconnectIfDue() {
        long now = System.currentTimeMillis();
        if (now < nextReconnectAttemptAt) {
            return;
        }
        try {
            canalClient.reconnect();
            nextReconnectAttemptAt = 0;
            log.info("Canal client reconnected; consumption will resume from the last acknowledged batch");
        } catch (Exception reconnectError) {
            nextReconnectAttemptAt = now + RECONNECT_BACKOFF_MILLIS;
            log.error("Canal reconnect failed; retrying after backoff", reconnectError);
        }
    }

    private void processEntry(List<CanalEntry.Entry> entries) throws Exception {
        for (CanalEntry.Entry entry : entries) {
            if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA) continue;
            CanalEntry.EventType eventType = entry.getHeader().getEventType();
            CanalEntry.RowChange rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                List<CanalEntry.Column> columns = eventType == CanalEntry.EventType.DELETE
                        ? rowData.getBeforeColumnsList()
                        : rowData.getAfterColumnsList();
                processEvent(parseColumns(columns), entry.getHeader().getTableName(), eventType);
            }
        }
    }

    private Map<String, Object> parseColumns(List<CanalEntry.Column> columns) {
        Map<String, Object> values = Maps.newHashMap();
        columns.forEach(column -> {
            if (column != null) values.put(column.getName(), column.getValue());
        });
        return values;
    }

    void processEvent(Map<String, Object> values, String table, CanalEntry.EventType eventType) {
        switch (table) {
            case "t_note" -> documentIndexRefreshService.refreshNote(requiredId(values, "id", table));
            case "t_user" -> documentIndexRefreshService.refreshUserAndNotes(requiredId(values, "id", table));
            case "t_note_count" -> documentIndexRefreshService.refreshNote(requiredId(values, "note_id", table));
            case "t_user_count" -> documentIndexRefreshService.refreshUser(requiredId(values, "user_id", table));
            default -> log.debug("Canal table ignored by Elasticsearch index sync: {} ({})", table, eventType);
        }
    }

    private long requiredId(Map<String, Object> values, String field, String table) {
        Object rawValue = values.get(field);
        if (rawValue == null) {
            throw new IllegalArgumentException("Canal event for " + table + " is missing " + field);
        }
        try {
            return Long.parseLong(rawValue.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Canal event for " + table + " has invalid " + field, e);
        }
    }
}
