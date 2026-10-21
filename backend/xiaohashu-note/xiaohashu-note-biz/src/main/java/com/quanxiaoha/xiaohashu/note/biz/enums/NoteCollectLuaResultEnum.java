package com.quanxiaoha.xiaohashu.note.biz.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Objects;

/**
 * 笔记收藏：执行 Lua 脚本返回结果
 */
@Getter
@AllArgsConstructor
public enum NoteCollectLuaResultEnum {
    NOT_EXIST(-1L),
    NOTE_COLLECTED(1L),
    NOTE_COLLECTED_SUCCESS(0L);

    private final Long code;

    public static NoteCollectLuaResultEnum valueOf(Long code) {
        for (NoteCollectLuaResultEnum result : values()) {
            if (Objects.equals(code, result.getCode())) {
                return result;
            }
        }
        return null;
    }
}
