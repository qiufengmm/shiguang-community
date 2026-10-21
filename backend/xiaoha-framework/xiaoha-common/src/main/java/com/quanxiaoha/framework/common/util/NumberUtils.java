package com.quanxiaoha.framework.common.util;

import java.math.RoundingMode;
import java.text.DecimalFormat;

public class NumberUtils {

    /**
     * 数字转换字符串
     *
     * @param number 数字
     * @return 格式化后的数字字符串
     */
    public static String formatNumberString(long number) {
        if (number < 10000) {
            return String.valueOf(number);
        } else if (number < 100000000) {
            double result = number / 10000.0;
            DecimalFormat df = new DecimalFormat("#.#");
            df.setRoundingMode(RoundingMode.DOWN);
            return df.format(result) + "万";
        } else {
            return "9999万";
        }
    }
}
