package com.quanxiaoha.xiaohashu.note.biz.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 笔记点赞、取消点赞操作类型
 */
@Getter
@AllArgsConstructor
public enum LikeUnlikeNoteTypeEnum {
    LIKE(1),
    UNLIKE(0),
    ;

    private final Integer code;
}
