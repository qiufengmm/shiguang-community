package com.quanxiaoha.xiaohashu.note.biz.convert;

import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteDO;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.PublishNoteDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NoteConvertTest {

    @Test
    void mapsNoteMetadataInBothDirectionsAndLeavesBodyForTheDto() {
        LocalDateTime createdAt = LocalDateTime.of(2025, 4, 17, 10, 30);
        NoteDO noteDO = NoteDO.builder()
                .id(991234567890111L)
                .title("mapping fixture")
                .isContentEmpty(false)
                .creatorId(992345678901122L)
                .topicId(12L)
                .topicName("topic")
                .isTop(false)
                .type(0)
                .imgUris("https://cdn.example/cover.jpg")
                .visible(0)
                .createTime(createdAt)
                .updateTime(createdAt)
                .status(1)
                .contentUuid("11111111-1111-4111-8111-111111111111")
                .build();

        PublishNoteDTO dto = NoteConvert.INSTANCE.convertDO2DTO(noteDO);
        dto.setContent("note body");
        NoteDO restored = NoteConvert.INSTANCE.convertDTO2DO(dto);

        assertEquals(noteDO.getId(), dto.getId());
        assertEquals(noteDO.getCreatorId(), dto.getCreatorId());
        assertEquals(noteDO.getContentUuid(), dto.getContentUuid());
        assertEquals("note body", dto.getContent());
        assertEquals(noteDO.getId(), restored.getId());
        assertEquals(noteDO.getTitle(), restored.getTitle());
        assertEquals(noteDO.getCreateTime(), restored.getCreateTime());
        assertEquals(noteDO.getContentUuid(), restored.getContentUuid());
        assertNull(dto.getVideoUri());
    }
}
