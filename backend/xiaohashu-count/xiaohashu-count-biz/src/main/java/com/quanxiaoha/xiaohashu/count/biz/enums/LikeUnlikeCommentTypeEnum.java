package com.quanxiaoha.xiaohashu.count.biz.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Objects;

@Getter
@AllArgsConstructor
public enum LikeUnlikeCommentTypeEnum {
    LIKE(1),
    UNLIKE(0);

    private final Integer code;

    public static LikeUnlikeCommentTypeEnum valueOf(Integer code) {
        for (LikeUnlikeCommentTypeEnum type : LikeUnlikeCommentTypeEnum.values()) {
            if (Objects.equals(code, type.getCode())) {
                return type;
            }
        }
        return null;
    }
}
