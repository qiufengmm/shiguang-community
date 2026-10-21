package com.quanxiaoha.xiaohashu.user.biz.exception;

import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.user.biz.enums.ResponseCodeEnum;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessExceptionPreservesUserErrorCodeAndMessage() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/users/profile");

        Response<Object> response = handler.handleBizException(
                request, new BizException(ResponseCodeEnum.PARAM_NOT_VALID));

        assertFalse(response.isSuccess());
        assertEquals("USER-10001", response.getErrorCode());
        assertEquals("参数错误", response.getMessage());
    }

    @Test
    void unexpectedExceptionReturnsUserSystemError() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/users/profile");

        Response<Object> response = handler.handleOtherException(request, new Exception("unexpected"));

        assertFalse(response.isSuccess());
        assertEquals("USER-10000", response.getErrorCode());
        assertEquals("出错啦，后台小哥正在努力修复中...", response.getMessage());
    }
}
