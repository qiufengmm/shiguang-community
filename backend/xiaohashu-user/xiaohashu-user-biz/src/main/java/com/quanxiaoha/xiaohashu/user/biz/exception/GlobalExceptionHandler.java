package com.quanxiaoha.xiaohashu.user.biz.exception;

import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.user.biz.enums.ResponseCodeEnum;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    @ResponseBody
    public Response<Object> handleBizException(HttpServletRequest request, BizException e) {
        log.warn("{} request fail, errorCode: {}, errorMessage: {}",
                request.getRequestURI(), e.getErrorCode(), e.getErrorMessage());
        return Response.fail(e);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    @ResponseBody
    public Response<Object> handleControllerException(HttpServletRequest request, Throwable e) {
        String errorCode = ResponseCodeEnum.PARAM_NOT_VALID.getErrorCode();
        BindingResult bindingResult = null;
        if (e instanceof MethodArgumentNotValidException) {
            bindingResult = ((MethodArgumentNotValidException) e).getBindingResult();
        } else if (e instanceof BindException) {
            bindingResult = ((BindException) e).getBindingResult();
        }

        StringBuilder errorMessage = new StringBuilder();
        if (bindingResult != null) {
            bindingResult.getFieldErrors().forEach(error -> errorMessage.append(error.getField())
                    .append(" ")
                    .append(error.getDefaultMessage())
                    .append(", 当前值: '")
                    .append(error.getRejectedValue())
                    .append("'; "));
        }

        log.warn("{} request error, errorCode: {}, errorMessage: {}",
                request.getRequestURI(), errorCode, errorMessage);
        return Response.fail(errorCode, errorMessage.toString());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public Response<Object> handleIllegalArgumentException(HttpServletRequest request, IllegalArgumentException e) {
        String errorCode = ResponseCodeEnum.PARAM_NOT_VALID.getErrorCode();
        String errorMessage = e.getMessage();
        log.warn("{} request error, errorCode: {}, errorMessage: {}",
                request.getRequestURI(), errorCode, errorMessage);
        return Response.fail(errorCode, errorMessage);
    }

    @ExceptionHandler(Exception.class)
    @ResponseBody
    public Response<Object> handleOtherException(HttpServletRequest request, Exception e) {
        log.error("{} request error", request.getRequestURI(), e);
        return Response.fail(ResponseCodeEnum.SYSTEM_ERROR);
    }
}
