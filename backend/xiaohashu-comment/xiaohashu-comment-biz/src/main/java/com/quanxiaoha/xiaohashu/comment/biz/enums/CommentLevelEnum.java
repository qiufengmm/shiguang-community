package com.quanxiaoha.xiaohashu.comment.biz.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Objects;

@Getter
@AllArgsConstructor
public enum CommentLevelEnum {
    ONE(1),
    TWO(2);

    private final Integer code;

    public static CommentLevelEnum valueOf(Integer code) {
        for (CommentLevelEnum commentLevelEnum : values()) {
            if (Objects.equals(code, commentLevelEnum.getCode())) {
                return commentLevelEnum;
            }
        }
        return null;
    }
}
