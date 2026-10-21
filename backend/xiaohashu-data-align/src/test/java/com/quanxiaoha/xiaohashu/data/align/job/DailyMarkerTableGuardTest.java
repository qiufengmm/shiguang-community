package com.quanxiaoha.xiaohashu.data.align.job;

import com.quanxiaoha.xiaohashu.data.align.domain.mapper.CreateTableMapper;
import com.quanxiaoha.xiaohashu.data.align.domain.mapper.TableExistenceMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DailyMarkerTableGuardTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 20);
    private static final String DATE_SUFFIX = "20261020";

    private final CreateTableMapper createTableMapper = mock(CreateTableMapper.class);
    private final TableExistenceMapper tableExistenceMapper = mock(TableExistenceMapper.class);
    private final DailyMarkerTableGuard guard =
            new DailyMarkerTableGuard(createTableMapper, tableExistenceMapper, 3);

    @Test
    void expectedTableNamesCoversEveryMetricAndShard() {
        List<String> names = guard.expectedTableNames(DATE_SUFFIX);

        assertEquals(CountAuditMetric.values().length * 3, names.size());
        assertTrue(names.contains("t_data_align_fans_count_temp_20261020_0"));
        assertTrue(names.contains("t_data_align_note_publish_count_temp_20261020_2"));
    }

    @Test
    void repairsEveryTableWhenNoneExist() {
        when(tableExistenceMapper.selectExistingTableNames(anyList())).thenReturn(List.of());

        int repaired = guard.checkAndRepair(DATE);

        assertEquals(21, repaired);
        verify(createTableMapper, times(3)).createDataAlignFansCountTempTable(anyString());
        verify(createTableMapper).createDataAlignFansCountTempTable("20261020_0");
        verify(createTableMapper).createDataAlignFansCountTempTable("20261020_1");
        verify(createTableMapper).createDataAlignFansCountTempTable("20261020_2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void repairsOnlyTheMissingTable() {
        when(tableExistenceMapper.selectExistingTableNames(anyList())).thenAnswer(invocation ->
                ((List<String>) invocation.getArgument(0)).stream()
                        .filter(name -> !name.equals("t_data_align_fans_count_temp_20261020_1"))
                        .toList());

        int repaired = guard.checkAndRepair(DATE);

        assertEquals(1, repaired);
        verify(createTableMapper).createDataAlignFansCountTempTable("20261020_1");
        verify(createTableMapper, times(1)).createDataAlignFansCountTempTable(anyString());
        verify(createTableMapper, never()).createDataAlignFollowingCountTempTable(anyString());
        verify(createTableMapper, never()).createDataAlignNotePublishCountTempTable(anyString());
    }

    @Test
    void doesNothingWhenAllTablesExist() {
        when(tableExistenceMapper.selectExistingTableNames(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        int repaired = guard.checkAndRepair(DATE);

        assertEquals(0, repaired);
        verifyNoInteractions(createTableMapper);
    }
}
