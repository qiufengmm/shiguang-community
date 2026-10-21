package com.quanxiaoha.xiaohashu.count.biz.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聚合后计数：点赞、取消点赞笔记
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AggregationCountLikeUnlikeNoteMqDTO {

    private Long creatorId;

    private Long noteId;

    private Integer count;
}
