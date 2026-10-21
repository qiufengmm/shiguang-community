package com.quanxiaoha.xiaohashu.comment.biz.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Retry configuration properties. */
@ConfigurationProperties(prefix = RetryProperties.PREFIX)
@Component
@Data
public class RetryProperties {

    public static final String PREFIX = "retry";

    /** Maximum number of attempts, including the first attempt. */
    private Integer maxAttempts = 3;

    /** Initial backoff interval in milliseconds. */
    private Integer initInterval = 1000;

    /** Exponential backoff multiplier. */
    private Double multiplier = 2.0;
}
