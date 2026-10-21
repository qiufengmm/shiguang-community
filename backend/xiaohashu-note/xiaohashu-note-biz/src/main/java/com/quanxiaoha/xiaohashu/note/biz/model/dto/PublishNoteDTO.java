package com.quanxiaoha.xiaohashu.note.biz.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 发布笔记事务消息
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PublishNoteDTO {
    private Long id;

    private String title;

    private Boolean isContentEmpty;

    private Long creatorId;

    private Long topicId;

    private String topicName;

    private Boolean isTop;

    private Integer type;

    private String imgUris;

    private String videoUri;

    private Integer visible;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer status;

    private String contentUuid;

    private String content;
}
