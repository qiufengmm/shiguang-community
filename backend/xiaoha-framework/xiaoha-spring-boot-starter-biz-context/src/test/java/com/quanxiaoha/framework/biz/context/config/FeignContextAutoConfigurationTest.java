package com.quanxiaoha.framework.biz.context.config;

import com.quanxiaoha.framework.biz.context.interceptor.FeignRequestInterceptor;
import feign.RequestInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class FeignContextAutoConfigurationTest {

    @Test
    void autoLoadsFeignRequestInterceptorFromStarterMetadata() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestApplication.class)
                .run(context -> assertInstanceOf(FeignRequestInterceptor.class,
                        context.getBean(RequestInterceptor.class)));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
