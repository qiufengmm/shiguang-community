package com.quanxiaoha.framework.biz.context.config;

import com.quanxiaoha.framework.biz.context.filter.HeaderUserId2ContextFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

@AutoConfiguration
public class BizContextAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(HeaderUserId2ContextFilter.class)
    public FilterRegistrationBean<HeaderUserId2ContextFilter> headerUserId2ContextFilter() {
        FilterRegistrationBean<HeaderUserId2ContextFilter> registration =
                new FilterRegistrationBean<>(new HeaderUserId2ContextFilter());
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
        return registration;
    }
}
