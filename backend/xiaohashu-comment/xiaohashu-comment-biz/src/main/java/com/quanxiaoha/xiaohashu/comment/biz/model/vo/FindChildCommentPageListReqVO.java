package com.quanxiaoha.xiaohashu.comment.biz.model.vo;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FindChildCommentPageListReqVO {

    @NotNull(message = "父评论 ID 不能为空")
    private Long parentCommentId;

    @NotNull(message = "页码不能为空")
    private Integer pageNo = 1;
}
