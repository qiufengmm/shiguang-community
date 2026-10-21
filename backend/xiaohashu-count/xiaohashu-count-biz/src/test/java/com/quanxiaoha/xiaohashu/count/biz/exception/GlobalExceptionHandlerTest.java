package com.quanxiaoha.xiaohashu.count.biz.exception;

import org.junit.jupiter.api.Test;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    @Test
    void runtimeExceptionUsesDedicatedRethrowingHandler() {
        ExceptionHandlerMethodResolver resolver = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

        var handler = resolver.resolveMethod(new RuntimeException());

        assertNotNull(handler);
        assertEquals("handleRuntimeException", handler.getName());
    }
}
