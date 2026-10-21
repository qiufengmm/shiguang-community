package com.quanxiaoha.xiaohashu.note.biz.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Objects;

/**
 * 笔记点赞 Lua 脚本返回结果
 */
@Getter
@AllArgsConstructor
public enum NoteLikeLuaResultEnum {
    NOT_EXIST(-1L),
    NOTE_LIKE_SUCCESS(0L),
    NOTE_LIKED(1L),
    ;

    private final Long code;

    public static NoteLikeLuaResultEnum valueOf(Long code) {
        for (NoteLikeLuaResultEnum result : NoteLikeLuaResultEnum.values()) {
            if (Objects.equals(code, result.getCode())) {
                return result;
            }
        }
        return null;
    }
}
