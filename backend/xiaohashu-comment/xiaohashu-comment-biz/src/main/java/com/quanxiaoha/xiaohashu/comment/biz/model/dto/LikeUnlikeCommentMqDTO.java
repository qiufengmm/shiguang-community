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
public class LikeUnlikeCommentMqDTO {

    private Long userId;

    private Long commentId;

    /**
     * 0: 取消点赞，1：点赞
     */
    private Integer type;

    private LocalDateTime createTime;
}
