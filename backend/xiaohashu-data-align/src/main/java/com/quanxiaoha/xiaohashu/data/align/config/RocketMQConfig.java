package com.quanxiaoha.xiaohashu.data.align.config;

import org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * RocketMQ 配置
 */
@Configuration
@Import(RocketMQAutoConfiguration.class)
public class RocketMQConfig {
}
