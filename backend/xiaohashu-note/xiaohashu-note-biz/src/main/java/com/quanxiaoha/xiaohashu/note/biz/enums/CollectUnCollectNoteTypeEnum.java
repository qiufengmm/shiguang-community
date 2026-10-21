package com.quanxiaoha.xiaohashu.note.biz.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 笔记收藏、取消收藏类型
 */
@Getter
@AllArgsConstructor
public enum CollectUnCollectNoteTypeEnum {
    COLLECT(1),
    UN_COLLECT(0);

    private final Integer code;
}
