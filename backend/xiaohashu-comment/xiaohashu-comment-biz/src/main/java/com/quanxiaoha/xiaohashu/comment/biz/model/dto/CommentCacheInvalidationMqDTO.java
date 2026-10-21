package com.quanxiaoha.xiaohashu.comment.biz.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CommentCacheInvalidationMqDTO {
    private String eventId;
    private Long commentId;
    private Long noteId;
    private Long parentId;
    private Integer level;
    private Boolean clearFirstReplyMarker;
}
