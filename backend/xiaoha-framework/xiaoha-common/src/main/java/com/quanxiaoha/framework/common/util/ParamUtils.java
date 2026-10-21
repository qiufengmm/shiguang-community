package com.quanxiaoha.framework.common.util;

import java.util.regex.Pattern;

/**
 * 参数条件工具。
 */
public final class ParamUtils {

    private static final int NICK_NAME_MIN_LENGTH = 2;
    private static final int NICK_NAME_MAX_LENGTH = 24;
    private static final Pattern NICK_NAME_SPECIAL_CHARACTERS =
            Pattern.compile("[!@#$%^&*(),.?\\\"/:{}|<>《]");

    private static final int ID_MIN_LENGTH = 6;
    private static final int ID_MAX_LENGTH = 15;
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");

    private ParamUtils() {
    }

    public static boolean checkNickname(String nickname) {
        if (nickname == null || nickname.length() < NICK_NAME_MIN_LENGTH
                || nickname.length() > NICK_NAME_MAX_LENGTH) {
            return false;
        }
        return !NICK_NAME_SPECIAL_CHARACTERS.matcher(nickname).find();
    }

    public static boolean checkXiaohashuId(String xiaohashuId) {
        if (xiaohashuId == null || xiaohashuId.length() < ID_MIN_LENGTH
                || xiaohashuId.length() > ID_MAX_LENGTH) {
            return false;
        }
        return ID_PATTERN.matcher(xiaohashuId).matches();
    }

    public static boolean checkLength(String value, int maxLength) {
        return value != null && !value.isEmpty() && value.length() <= maxLength;
    }
}
