package com.quanxiaoha.xiaohashu.search.biz.canal;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.quanxiaoha.xiaohashu.search.biz.service.DocumentIndexRefreshService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CanalScheduleCountEventTest {

    @Test
    void noteCountChangesRefreshTheOwningNoteDocument() throws Exception {
        DocumentIndexRefreshService refreshService = mock(DocumentIndexRefreshService.class);
        CanalSchedule schedule = new CanalSchedule();
        ReflectionTestUtils.setField(schedule, "documentIndexRefreshService", refreshService);

        schedule.processEvent(Map.of("note_id", "123456"), "t_note_count", CanalEntry.EventType.UPDATE);

        verify(refreshService).refreshNote(123456L);
    }

    @Test
    void userCountChangesRefreshTheOwningUserDocument() throws Exception {
        DocumentIndexRefreshService refreshService = mock(DocumentIndexRefreshService.class);
        CanalSchedule schedule = new CanalSchedule();
        ReflectionTestUtils.setField(schedule, "documentIndexRefreshService", refreshService);

        schedule.processEvent(Map.of("user_id", "654321"), "t_user_count", CanalEntry.EventType.UPDATE);

        verify(refreshService).refreshUser(654321L);
    }
}
