package com.quanxiaoha.xiaohashu.count.biz.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Objects;

/**
 * 笔记点赞、取消点赞类型
 */
@Getter
@AllArgsConstructor
public enum LikeUnlikeNoteTypeEnum {
    LIKE(1),
    UNLIKE(0);

    private final Integer code;

    public static LikeUnlikeNoteTypeEnum valueOf(Integer code) {
        for (LikeUnlikeNoteTypeEnum type : values()) {
            if (Objects.equals(code, type.getCode())) {
                return type;
            }
        }
        return null;
    }
}
