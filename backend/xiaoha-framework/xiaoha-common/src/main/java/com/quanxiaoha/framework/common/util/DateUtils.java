package com.quanxiaoha.framework.common.util;

import com.quanxiaoha.framework.common.constant.DateConstants;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * 日期工具类
 **/
public class DateUtils {

    /**
     * LocalDateTime 转时间戳
     *
     * @param localDateTime
     * @return
     */
    public static long localDateTime2Timestamp(LocalDateTime localDateTime) {
        return localDateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /**
     * 计算年龄
     *
     * @param birthDate 出生日期
     * @return 年龄
     */
    public static int calculateAge(LocalDate birthDate) {
        return Period.between(birthDate, LocalDate.now()).getYears();
    }

    /**
     * LocalDateTime 转 String 字符串
     *
     * @param time 时间
     * @return 格式化后的时间
     */
    public static String localDateTime2String(LocalDateTime time) {
        return time.format(DateConstants.DATE_FORMAT_Y_M_D_H_M_S);
    }

    /**
     * LocalDateTime 转友好的相对时间字符串
     *
     * @param dateTime 时间
     * @return 相对时间文案
     */
    public static String formatRelativeTime(LocalDateTime dateTime) {
        LocalDateTime now = LocalDateTime.now();

        long daysDiff = ChronoUnit.DAYS.between(dateTime, now);
        long hoursDiff = ChronoUnit.HOURS.between(dateTime, now);
        long minutesDiff = ChronoUnit.MINUTES.between(dateTime, now);

        if (daysDiff < 1) {
            if (hoursDiff < 1) {
                return minutesDiff + "分钟前";
            } else {
                return hoursDiff + "小时前";
            }
        } else if (daysDiff == 1) {
            return "昨天 " + dateTime.format(DateConstants.DATE_FORMAT_H_M);
        } else if (daysDiff < 7) {
            return daysDiff + "天前";
        } else if (dateTime.getYear() == now.getYear()) {
            return dateTime.format(DateConstants.DATE_FORMAT_M_D);
        } else {
            return dateTime.format(DateConstants.DATE_FORMAT_Y_M_D);
        }
    }
}
