package com.quanxiaoha.xiaohashu.search.biz.enums;

import com.quanxiaoha.framework.common.exception.BaseExceptionInterface;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ResponseCodeEnum implements BaseExceptionInterface {

    SYSTEM_ERROR("SEARCH-10000", "出错啦，后台小哥正在努力修复中..."),
    PARAM_NOT_VALID("SEARCH-10001", "参数错误");

    private final String errorCode;
    private final String errorMessage;
}
