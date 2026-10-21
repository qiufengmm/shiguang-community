package com.quanxiaoha.xiaohashu.note.biz.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 笔记操作类型
 */
@Getter
@AllArgsConstructor
public enum NoteOperateEnum {
    PUBLISH(1),
    DELETE(0),
    CACHE_INVALIDATION(2);

    private final Integer code;
}
