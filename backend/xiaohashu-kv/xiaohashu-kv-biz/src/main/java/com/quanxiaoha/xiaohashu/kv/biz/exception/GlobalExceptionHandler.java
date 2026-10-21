package com.quanxiaoha.xiaohashu.kv.biz.exception;

import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.kv.biz.enums.ResponseCodeEnum;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Optional;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Response<Object> handleBizException(HttpServletRequest request, BizException exception) {
        log.warn("{} request failed, errorCode: {}, errorMessage: {}",
                request.getRequestURI(), exception.getErrorCode(), exception.getErrorMessage());
        return Response.fail(exception);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Response<Object> handleMethodArgumentNotValidException(
            HttpServletRequest request, MethodArgumentNotValidException exception) {
        StringBuilder message = new StringBuilder();
        BindingResult bindingResult = exception.getBindingResult();
        Optional.ofNullable(bindingResult.getFieldErrors()).ifPresent(errors -> errors.forEach(error ->
                message.append(error.getField())
                        .append(' ')
                        .append(error.getDefaultMessage())
                        .append(", 当前值: '")
                        .append(error.getRejectedValue())
                        .append("'; ")));

        String errorCode = ResponseCodeEnum.PARAM_NOT_VALID.getErrorCode();
        log.warn("{} request invalid, errorCode: {}, errorMessage: {}",
                request.getRequestURI(), errorCode, message);
        return Response.fail(errorCode, message.toString());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Response<Object> handleIllegalArgumentException(
            HttpServletRequest request, IllegalArgumentException exception) {
        String errorCode = ResponseCodeEnum.PARAM_NOT_VALID.getErrorCode();
        log.warn("{} request invalid, errorCode: {}, errorMessage: {}",
                request.getRequestURI(), errorCode, exception.getMessage());
        return Response.fail(errorCode, exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Response<Object> handleOtherException(HttpServletRequest request, Exception exception) {
        log.error("{} request failed", request.getRequestURI(), exception);
        return Response.fail(ResponseCodeEnum.SYSTEM_ERROR);
    }
}
