package com.quanxiaoha.xiaohashu.data.align.constant;

/**
 * 表常量
 */
public class TableConstants {

    private static final String TABLE_NAME_SEPARATE = "_";

    public static String buildTableNameSuffix(String date, long hashKey) {
        return date + TABLE_NAME_SEPARATE + hashKey;
    }
}
