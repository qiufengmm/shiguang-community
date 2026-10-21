package com.quanxiaoha.xiaohashu.search.biz.service;

import static com.quanxiaoha.xiaohashu.search.biz.constant.SearchPageConstants.MAX_OFFSET;
import static com.quanxiaoha.xiaohashu.search.biz.constant.SearchPageConstants.MAX_PAGE_NO;
import static com.quanxiaoha.xiaohashu.search.biz.constant.SearchPageConstants.PAGE_SIZE;

/**
 * Shared offset pagination limits for Elasticsearch searches.
 */
public final class SearchPageSupport {

    private SearchPageSupport() {
    }

    public static int offsetFor(Integer pageNo) {
        if (pageNo == null || pageNo < 1 || pageNo > MAX_PAGE_NO) {
            throw new IllegalArgumentException(String.format(
                    "页码必须在 1 到 %d 之间，最大偏移量为 %d", MAX_PAGE_NO, MAX_OFFSET));
        }
        return (pageNo - 1) * PAGE_SIZE;
    }
}
