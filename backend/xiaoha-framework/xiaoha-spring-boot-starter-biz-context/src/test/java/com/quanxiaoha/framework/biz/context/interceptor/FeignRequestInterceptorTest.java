package com.quanxiaoha.framework.biz.context.interceptor;

import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.framework.common.constant.GlobalConstants;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FeignRequestInterceptorTest {

    @AfterEach
    void clearUserContext() {
        LoginUserContextHolder.remove();
    }

    @Test
    void addsCurrentUserIdToOutgoingFeignRequest() {
        LoginUserContextHolder.setUserId(135L);
        RequestTemplate request = new RequestTemplate();

        new FeignRequestInterceptor().apply(request);

        assertEquals(List.of("135"), request.headers().get(GlobalConstants.USER_ID));
    }

    @Test
    void omitsUserIdHeaderWhenContextIsEmpty() {
        RequestTemplate request = new RequestTemplate();

        new FeignRequestInterceptor().apply(request);

        assertFalse(request.headers().containsKey(GlobalConstants.USER_ID));
    }
}
