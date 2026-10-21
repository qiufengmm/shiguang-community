package com.quanxiaoha.xiaohashu.count.biz.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CountLikeUnlikeCommentMqDTO {

    private Long userId;

    private Long commentId;

    /**
     * 0: 取消点赞，1: 点赞
     */
    private Integer type;
}
