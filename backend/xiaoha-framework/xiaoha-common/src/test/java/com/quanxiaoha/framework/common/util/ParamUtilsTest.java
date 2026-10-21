package com.quanxiaoha.framework.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParamUtilsTest {

    @Test
    void acceptsNicknameAtBothLengthBoundaries() {
        assertTrue(ParamUtils.checkNickname("小明"));
        assertTrue(ParamUtils.checkNickname("小".repeat(24)));
    }

    @Test
    void rejectsNicknameOutsideLengthRangeOrWithForbiddenCharacters() {
        assertFalse(ParamUtils.checkNickname("明"));
        assertFalse(ParamUtils.checkNickname("小".repeat(25)));
        assertFalse(ParamUtils.checkNickname("小明@"));
        assertFalse(ParamUtils.checkNickname(null));
    }

    @Test
    void acceptsOnlySixToFifteenCharacterIdsUsingLettersDigitsAndUnderscores() {
        assertTrue(ParamUtils.checkXiaohashuId("user_01"));
        assertTrue(ParamUtils.checkXiaohashuId("a".repeat(15)));
        assertFalse(ParamUtils.checkXiaohashuId("abc12"));
        assertFalse(ParamUtils.checkXiaohashuId("a".repeat(16)));
        assertFalse(ParamUtils.checkXiaohashuId("user-01"));
        assertFalse(ParamUtils.checkXiaohashuId(null));
    }

    @Test
    void acceptsNonEmptyTextUpToMaximumLength() {
        assertTrue(ParamUtils.checkLength("a", 100));
        assertTrue(ParamUtils.checkLength("a".repeat(100), 100));
        assertFalse(ParamUtils.checkLength("", 100));
        assertFalse(ParamUtils.checkLength("a".repeat(101), 100));
        assertFalse(ParamUtils.checkLength(null, 100));
    }
}
