package com.quanxiaoha.xiaohashu.note.biz.model.vo;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 收藏笔记
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CollectNoteReqVO {

    @NotNull(message = "笔记 ID 不能为空")
    private Long id;
}
