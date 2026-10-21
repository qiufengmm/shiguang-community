package com.quanxiaoha.xiaohashu.note.biz.service;

import com.quanxiaoha.xiaohashu.note.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteOperateOutboxDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteOperateOutboxMapper;
import com.quanxiaoha.xiaohashu.note.biz.enums.NoteOperateEnum;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class NoteOperateOutboxServiceTest {

    @Test
    void storesCacheInvalidationMessagesForReliableDelayedDelivery() {
        NoteOperateOutboxMapper mapper = mock(NoteOperateOutboxMapper.class);
        NoteOperateOutboxService service = new NoteOperateOutboxService();
        ReflectionTestUtils.setField(service, "outboxMapper", mapper);
        Long userId = 77L;
        Long noteId = 88L;
        LocalDateTime availableAt = LocalDateTime.now().plusSeconds(1);

        service.enqueueCacheInvalidation(userId, noteId, MQConstants.TOPIC_DELETE_NOTE_LOCAL_CACHE,
                noteId.toString(), availableAt);
        service.enqueueCacheInvalidation(userId, noteId, MQConstants.TOPIC_DELAY_DELETE_NOTE_REDIS_CACHE,
                "[88,77]", availableAt);

        org.mockito.ArgumentCaptor<NoteOperateOutboxDO> recordCaptor =
                org.mockito.ArgumentCaptor.forClass(NoteOperateOutboxDO.class);
        verify(mapper, times(2)).insertIfAbsent(recordCaptor.capture());
        var records = recordCaptor.getAllValues();
        assertEquals(NoteOperateEnum.CACHE_INVALIDATION.getCode(), records.get(0).getOperationType());
        assertEquals(userId, records.get(0).getCreatorId());
        assertEquals(noteId, records.get(0).getNoteId());
        assertEquals(MQConstants.TOPIC_DELETE_NOTE_LOCAL_CACHE, records.get(0).getTopic());
        assertEquals(noteId.toString(), records.get(0).getPayload());
        assertEquals(availableAt, records.get(0).getNextAttemptAt());
        assertEquals(MQConstants.TOPIC_DELAY_DELETE_NOTE_REDIS_CACHE, records.get(1).getTopic());
        assertTrue(records.stream().allMatch(record -> record.getEventId().startsWith("note-cache:88:")));
    }
}
