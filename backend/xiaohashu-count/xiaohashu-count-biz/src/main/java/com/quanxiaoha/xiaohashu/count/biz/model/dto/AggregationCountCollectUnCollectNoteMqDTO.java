package com.quanxiaoha.xiaohashu.count.biz.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聚合后计数：收藏、取消收藏笔记
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AggregationCountCollectUnCollectNoteMqDTO {

    private Long creatorId;

    private Long noteId;

    private Integer count;
}
