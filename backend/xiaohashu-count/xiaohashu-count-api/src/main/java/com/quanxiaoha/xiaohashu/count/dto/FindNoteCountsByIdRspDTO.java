package com.quanxiaoha.xiaohashu.count.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 查询笔记维度相关计数
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FindNoteCountsByIdRspDTO {

    private Long noteId;
    private Long likeTotal;
    private Long collectTotal;
    private Long commentTotal;
}
