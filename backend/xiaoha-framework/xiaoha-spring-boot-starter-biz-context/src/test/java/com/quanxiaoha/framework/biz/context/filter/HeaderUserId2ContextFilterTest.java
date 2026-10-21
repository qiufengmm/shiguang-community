package com.quanxiaoha.framework.biz.context.filter;

import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.framework.common.constant.GlobalConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HeaderUserId2ContextFilterTest {

    @AfterEach
    void clearContext() {
        LoginUserContextHolder.remove();
    }

    @Test
    void readsUserIdDuringRequestAndClearsItAfterward() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(GlobalConstants.USER_ID, "42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Long> userIdSeenByChain = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) ->
                userIdSeenByChain.set(LoginUserContextHolder.getUserId());

        new HeaderUserId2ContextFilter().doFilter(request, response, chain);

        assertEquals(42L, userIdSeenByChain.get());
        assertNull(LoginUserContextHolder.getUserId());
    }
}
