package com.quanxiaoha.xiaohashu.data.align.job;

import com.quanxiaoha.xiaohashu.data.align.domain.mapper.CountAuditMapper;
import com.quanxiaoha.xiaohashu.data.align.domain.mapper.CountConsumeLogCleanMapper;
import com.quanxiaoha.xiaohashu.data.align.domain.mapper.DeleteTableMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeleteTableXxlJobTest {

    @Test
    void dropsExpiredTableOnlyAfterFinalAuditFindsNoMismatches() {
        Fixture fixture = new Fixture(0);

        fixture.job.deleteTableJobHandler();

        verify(fixture.auditService).auditTable(CountAuditMetric.FANS, fixture.tableName);
        verify(fixture.deleteTableMapper).deleteDataAlignFansCountTempTable(fixture.suffix);
    }

    @Test
    void retainsExpiredTableWhenFinalAuditFindsMismatches() {
        Fixture fixture = new Fixture(2);

        fixture.job.deleteTableJobHandler();

        verify(fixture.auditService).auditTable(CountAuditMetric.FANS, fixture.tableName);
        verify(fixture.deleteTableMapper, never()).deleteDataAlignFansCountTempTable(fixture.suffix);
    }

    @Test
    void prunesExpiredConsumeIdempotencyLogOnEveryRun() {
        Fixture fixture = new Fixture(0);

        fixture.job.deleteTableJobHandler();

        verify(fixture.countConsumeLogCleanMapper).deleteExpired(anyInt(), anyInt());
    }

    private static final class Fixture {
        final String suffix = LocalDate.now().minusMonths(2).format(DateTimeFormatter.BASIC_ISO_DATE) + "_1";
        final String tableName = CountAuditMetric.FANS.getTablePrefix() + suffix;
        final CountAuditMapper countAuditMapper = mock(CountAuditMapper.class);
        final CountReconciliationAuditService auditService = mock(CountReconciliationAuditService.class);
        final DeleteTableMapper deleteTableMapper = mock(DeleteTableMapper.class);
        final CountConsumeLogCleanMapper countConsumeLogCleanMapper = mock(CountConsumeLogCleanMapper.class);
        final DeleteTableXxlJob job = new DeleteTableXxlJob(countAuditMapper, auditService,
                deleteTableMapper, countConsumeLogCleanMapper) {
            @Override
            protected void logToJob(String message, Object... arguments) { }
        };

        Fixture(long mismatches) {
            when(countAuditMapper.selectMarkerTables(anyString())).thenAnswer(invocation ->
                    CountAuditMetric.FANS.getTablePrefix().equals(invocation.getArgument(0))
                            ? List.of(tableName) : List.of());
            when(auditService.auditTable(CountAuditMetric.FANS, tableName))
                    .thenReturn(new CountReconciliationAuditService.AuditSummary(1, 10, mismatches));
        }
    }
}
