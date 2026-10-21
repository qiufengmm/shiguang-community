package com.quanxiaoha.xiaohashu.note.biz.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Objects;

/**
 * 笔记取消收藏：执行 Lua 脚本返回结果
 */
@Getter
@AllArgsConstructor
public enum NoteUnCollectLuaResultEnum {
    NOT_EXIST(-1L),
    NOTE_COLLECTED(1L),
    NOTE_NOT_COLLECTED(0L);

    private final Long code;

    public static NoteUnCollectLuaResultEnum valueOf(Long code) {
        for (NoteUnCollectLuaResultEnum result : values()) {
            if (Objects.equals(code, result.getCode())) {
                return result;
            }
        }
        return null;
    }
}
