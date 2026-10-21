package com.quanxiaoha.xiaohashu.comment.biz.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PublishCommentMqDTO {

    private Long commentId;

    private Long noteId;

    private String content;

    private String contentUuid;

    private String imageUrl;

    private Long replyCommentId;

    private LocalDateTime createTime;

    private Long creatorId;
}
