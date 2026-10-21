package com.quanxiaoha.xiaohashu.comment.biz.model.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 评论热度更新数据。 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CommentHeatBO {
    private Long id;

    private Long noteId;

    private Double heat;
}
