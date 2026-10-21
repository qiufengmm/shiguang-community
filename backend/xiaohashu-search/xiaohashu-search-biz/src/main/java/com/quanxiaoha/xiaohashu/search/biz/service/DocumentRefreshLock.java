package com.quanxiaoha.xiaohashu.search.biz.service;

public interface DocumentRefreshLock {
    void withLock(String key, Runnable refresh);
}
