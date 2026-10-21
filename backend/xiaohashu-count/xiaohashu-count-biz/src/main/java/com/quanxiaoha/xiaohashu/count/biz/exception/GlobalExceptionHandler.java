package com.quanxiaoha.xiaohashu.count.biz.exception;

import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.biz.enums.ResponseCodeEnum;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Optional;

/**
 * 全局异常处理
 **/
@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 捕获自定义业务异常
     */
    @ExceptionHandler({BizException.class})
    @ResponseBody
    public Response<Object> handleBizException(HttpServletRequest request, BizException e) {
        log.warn("{} request fail, errorCode: {}, errorMessage: {}",
                request.getRequestURI(), e.getErrorCode(), e.getErrorMessage());
        return Response.fail(e);
    }

    /**
     * 捕获参数校验异常
     */
    @ExceptionHandler({MethodArgumentNotValidException.class})
    @ResponseBody
    public Response<Object> handleMethodArgumentNotValidException(
            HttpServletRequest request, MethodArgumentNotValidException e) {
        String errorCode = ResponseCodeEnum.PARAM_NOT_VALID.getErrorCode();
        BindingResult bindingResult = e.getBindingResult();
        StringBuilder sb = new StringBuilder();

        Optional.ofNullable(bindingResult.getFieldErrors()).ifPresent(errors -> {
            errors.forEach(error -> sb.append(error.getField())
                    .append(" ")
                    .append(error.getDefaultMessage())
                    .append(", 当前值: '")
                    .append(error.getRejectedValue())
                    .append("'; "));
        });

        String errorMessage = sb.toString();
        log.warn("{} request error, errorCode: {}, errorMessage: {}",
                request.getRequestURI(), errorCode, errorMessage);
        return Response.fail(errorCode, errorMessage);
    }

    /**
     * 捕获参数校验异常
     */
    @ExceptionHandler({IllegalArgumentException.class})
    @ResponseBody
    public Response<Object> handleIllegalArgumentException(HttpServletRequest request, IllegalArgumentException e) {
        String errorCode = ResponseCodeEnum.PARAM_NOT_VALID.getErrorCode();
        String errorMessage = e.getMessage();
        log.warn("{} request error, errorCode: {}, errorMessage: {}",
                request.getRequestURI(), errorCode, errorMessage);
        return Response.fail(errorCode, errorMessage);
    }

    /**
     * 熔断：异常测试
     */
    @ExceptionHandler({RuntimeException.class})
    @ResponseBody
    public Response<Object> handleRuntimeException(HttpServletRequest request, Exception e) throws Exception {
        throw e;
    }

    /**
     * 其他类型异常
     */
    @ExceptionHandler({Exception.class})
    @ResponseBody
    public Response<Object> handleOtherException(HttpServletRequest request, Exception e) {
        log.error("{} request error, ", request.getRequestURI(), e);
        return Response.fail(ResponseCodeEnum.SYSTEM_ERROR);
    }
}
