package com.quanxiaoha.xiaohashu.kv.biz.enums;

import com.quanxiaoha.framework.common.exception.BaseExceptionInterface;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ResponseCodeEnum implements BaseExceptionInterface {

    SYSTEM_ERROR("KV-10000", "出错啦，后台小哥正在努力修复中..."),
    PARAM_NOT_VALID("KV-10001", "参数错误"),
    NOTE_CONTENT_NOT_FOUND("KV-20000", "该笔记内容不存在"),
    NOTE_CONTENT_VERSION_RETIRED("KV-20001", "该笔记正文版本已失效");

    private final String errorCode;
    private final String errorMessage;
}
