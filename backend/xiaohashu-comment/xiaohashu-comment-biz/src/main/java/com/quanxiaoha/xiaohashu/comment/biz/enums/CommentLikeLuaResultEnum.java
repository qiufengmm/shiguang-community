package com.quanxiaoha.xiaohashu.comment.biz.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Objects;

@Getter
@AllArgsConstructor
public enum CommentLikeLuaResultEnum {
    NOT_EXIST(-1L),
    COMMENT_LIKED(1L),
    COMMENT_LIKE_SUCCESS(0L);

    private final Long code;

    public static CommentLikeLuaResultEnum valueOf(Long code) {
        for (CommentLikeLuaResultEnum result : values()) {
            if (Objects.equals(code, result.getCode())) {
                return result;
            }
        }
        return null;
    }
}
