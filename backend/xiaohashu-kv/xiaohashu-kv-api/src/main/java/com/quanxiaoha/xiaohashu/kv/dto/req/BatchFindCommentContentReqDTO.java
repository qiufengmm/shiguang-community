package com.quanxiaoha.xiaohashu.kv.dto.req;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 批量查询评论内容请求。 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class BatchFindCommentContentReqDTO {

    @NotNull(message = "评论 ID 不能为空")
    private Long noteId;

    @NotEmpty(message = "评论内容 Key 集合")
    @Valid
    private List<FindCommentContentReqDTO> commentContentKeys;
}
