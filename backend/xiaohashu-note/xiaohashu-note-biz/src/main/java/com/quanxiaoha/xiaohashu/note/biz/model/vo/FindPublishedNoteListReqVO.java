package com.quanxiaoha.xiaohashu.note.biz.model.vo;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 个人主页已发布笔记列表请求参数
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FindPublishedNoteListReqVO {

    @NotNull(message = "用户 ID 不能为空")
    private Long userId;

    /** 游标，即笔记 ID */
    private Long cursor;
}
