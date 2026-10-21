package com.quanxiaoha.xiaohashu.user.biz.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SexEnumTest {

    @Test
    void acceptsOnlyDefinedSexValues() {
        assertTrue(SexEnum.isValid(0));
        assertTrue(SexEnum.isValid(1));
        assertFalse(SexEnum.isValid(2));
        assertFalse(SexEnum.isValid(null));
    }
}
