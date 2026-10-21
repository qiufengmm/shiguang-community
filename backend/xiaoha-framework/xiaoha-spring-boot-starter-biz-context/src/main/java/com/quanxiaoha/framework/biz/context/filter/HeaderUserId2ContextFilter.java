package com.quanxiaoha.framework.biz.context.filter;

import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.framework.common.constant.GlobalConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 将网关透传的用户 ID 放入请求上下文。
 */
public class HeaderUserId2ContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String userId = request.getHeader(GlobalConstants.USER_ID);
        try {
            if (StringUtils.isNotBlank(userId)) {
                LoginUserContextHolder.setUserId(userId);
            }
            filterChain.doFilter(request, response);
        } finally {
            LoginUserContextHolder.remove();
        }
    }
}
