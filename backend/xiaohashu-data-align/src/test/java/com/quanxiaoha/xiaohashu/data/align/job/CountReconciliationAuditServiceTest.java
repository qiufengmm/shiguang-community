package com.quanxiaoha.xiaohashu.data.align.job;

import com.quanxiaoha.xiaohashu.data.align.domain.mapper.CountAuditMapper;
import com.quanxiaoha.xiaohashu.data.align.domain.mapper.CountAuditMarker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CountReconciliationAuditServiceTest {

    @Test
    void aSingleExecutorRepairsEveryMarkedMismatchAcrossPhysicalShards() {
        CountAuditMapper mapper = mock(CountAuditMapper.class);
        CountReconciliationRepairService repairService = mock(CountReconciliationRepairService.class);
        when(mapper.selectMarkerTables(CountAuditMetric.FANS.getTablePrefix())).thenReturn(List.of(
                "t_data_align_fans_count_temp_20261017_0",
                "t_data_align_fans_count_temp_20261017_1",
                "t_data_align_fans_count_temp_20261017_2"));
        when(mapper.selectBatch(anyString(), anyString(), anyLong(), anyInt())).thenAnswer(invocation -> {
            String tableName = invocation.getArgument(0);
            long lastRowId = invocation.getArgument(2);
            if (lastRowId != 0) return List.of();
            int physicalShard = Integer.parseInt(tableName.substring(tableName.lastIndexOf('_') + 1));
            return List.of(new CountAuditMarker(1L, 2000L + physicalShard));
        });
        when(mapper.selectSourceCount(eq(CountAuditMetric.FANS.name()), anyLong())).thenReturn(11L);
        when(mapper.selectStoredCount(eq(CountAuditMetric.FANS.name()), anyLong())).thenReturn(9L);
        when(repairService.repair(eq(CountAuditMetric.FANS), anyLong()))
                .thenReturn(new CountReconciliationRepairService.RepairResult(11L, 9L, true));

        CountReconciliationAuditService service = new CountReconciliationAuditService(mapper, repairService, 100);

        CountReconciliationAuditService.AuditSummary result = service.audit(CountAuditMetric.FANS, 0, 1);

        assertThat(result.scannedEntities()).isEqualTo(3);
        assertThat(result.mismatches()).isEqualTo(3);
        assertThat(result.repaired()).isEqualTo(3);
        verify(repairService).repair(CountAuditMetric.FANS, 2000L);
        verify(repairService).repair(CountAuditMetric.FANS, 2001L);
        verify(repairService).repair(CountAuditMetric.FANS, 2002L);
        verify(mapper).selectBatch("t_data_align_fans_count_temp_20261017_0", "user_id", 0L, 100);
        verify(mapper).selectBatch("t_data_align_fans_count_temp_20261017_1", "user_id", 0L, 100);
        verify(mapper).selectBatch("t_data_align_fans_count_temp_20261017_2", "user_id", 0L, 100);
    }

    @Test
    void executorShardAssignmentUsesPhysicalTableIndexModuloExecutorCount() {
        CountAuditMapper mapper = mock(CountAuditMapper.class);
        CountReconciliationRepairService repairService = mock(CountReconciliationRepairService.class);
        when(mapper.selectMarkerTables(CountAuditMetric.NOTE_LIKE.getTablePrefix())).thenReturn(List.of(
                "t_data_align_note_like_count_temp_20261017_0",
                "t_data_align_note_like_count_temp_20261017_1",
                "t_data_align_note_like_count_temp_20261017_2"));
        when(mapper.selectBatch(anyString(), anyString(), anyLong(), anyInt())).thenReturn(List.of());

        CountReconciliationAuditService service = new CountReconciliationAuditService(mapper, repairService, 100);
        service.audit(CountAuditMetric.NOTE_LIKE, 0, 2);

        verify(mapper).selectBatch("t_data_align_note_like_count_temp_20261017_0", "note_id", 0L, 100);
        verify(mapper).selectBatch("t_data_align_note_like_count_temp_20261017_2", "note_id", 0L, 100);
        verifyNoInteractions(repairService);
    }
}
