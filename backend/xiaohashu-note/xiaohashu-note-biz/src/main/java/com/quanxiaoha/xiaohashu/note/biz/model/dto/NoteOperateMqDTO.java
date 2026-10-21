package com.quanxiaoha.xiaohashu.note.biz.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 笔记发布、删除消息
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class NoteOperateMqDTO {

    private String eventId;

    private Long creatorId;

    private Long noteId;

    /**
     * 0：删除，1：发布
     */
    private Integer type;
}
