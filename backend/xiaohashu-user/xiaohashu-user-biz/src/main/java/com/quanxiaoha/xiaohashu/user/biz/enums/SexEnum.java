package com.quanxiaoha.xiaohashu.user.biz.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Objects;

@Getter
@AllArgsConstructor
public enum SexEnum {
    WOMAN(0),
    MAN(1);

    private final Integer value;

    public static boolean isValid(Integer value) {
        for (SexEnum sex : values()) {
            if (Objects.equals(value, sex.value)) {
                return true;
            }
        }
        return false;
    }
}
