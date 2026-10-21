package com.quanxiaoha.xiaohashu.comment.biz.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Objects;

@Getter
@AllArgsConstructor
public enum CommentUnlikeLuaResultEnum {
    NOT_EXIST(-1L),
    COMMENT_LIKED(1L),
    COMMENT_NOT_LIKED(0L);

    private final Long code;

    public static CommentUnlikeLuaResultEnum valueOf(Long code) {
        for (CommentUnlikeLuaResultEnum result : CommentUnlikeLuaResultEnum.values()) {
            if (Objects.equals(code, result.getCode())) {
                return result;
            }
        }
        return null;
    }
}
