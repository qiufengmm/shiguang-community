package com.quanxiaoha.xiaohashu.note.biz.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Objects;

/**
 * 取消点赞 Lua 脚本返回结果
 */
@Getter
@AllArgsConstructor
public enum NoteUnlikeLuaResultEnum {
    NOT_EXIST(-1L),
    NOTE_LIKED(1L),
    NOTE_NOT_LIKED(0L),
    ;

    private final Long code;

    public static NoteUnlikeLuaResultEnum valueOf(Long code) {
        for (NoteUnlikeLuaResultEnum result : NoteUnlikeLuaResultEnum.values()) {
            if (Objects.equals(code, result.getCode())) {
                return result;
            }
        }
        return null;
    }
}
