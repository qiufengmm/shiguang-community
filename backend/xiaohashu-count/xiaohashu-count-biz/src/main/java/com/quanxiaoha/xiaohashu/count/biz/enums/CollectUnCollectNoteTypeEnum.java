package com.quanxiaoha.xiaohashu.count.biz.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Objects;

/**
 * 笔记收藏、取消收藏类型
 */
@Getter
@AllArgsConstructor
public enum CollectUnCollectNoteTypeEnum {
    COLLECT(1),
    UN_COLLECT(0);

    private final Integer code;

    public static CollectUnCollectNoteTypeEnum valueOf(Integer code) {
        for (CollectUnCollectNoteTypeEnum type : values()) {
            if (Objects.equals(code, type.getCode())) {
                return type;
            }
        }
        return null;
    }
}
