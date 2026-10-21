package com.quanxiaoha.xiaohashu.count.biz.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CountPublishCommentMqDTO {

    private String eventId;

    private Long noteId;

    private Long commentId;

    private Integer level;

    private Long parentId;
}
