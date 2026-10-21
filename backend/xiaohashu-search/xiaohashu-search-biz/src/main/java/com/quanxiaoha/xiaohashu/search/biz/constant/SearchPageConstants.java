package com.quanxiaoha.xiaohashu.search.biz.constant;

public final class SearchPageConstants {

    public static final int PAGE_SIZE = 10;
    public static final int MAX_RESULT_WINDOW = 10_000;
    public static final int MAX_OFFSET = MAX_RESULT_WINDOW - PAGE_SIZE;
    public static final int MAX_PAGE_NO = MAX_OFFSET / PAGE_SIZE + 1;

    private SearchPageConstants() {
    }
}
