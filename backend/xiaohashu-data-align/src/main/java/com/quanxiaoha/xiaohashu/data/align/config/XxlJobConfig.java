package com.quanxiaoha.xiaohashu.data.align.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * XXL-JOB 执行器配置
 */
@Configuration
@Slf4j
public class XxlJobConfig {

    @Resource
    private XxlJobProperties xxlJobProperties;

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        log.info(">>>>>>>>>>> xxl-job config init.");
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(xxlJobProperties.getAdminAddresses());
        executor.setAppname(xxlJobProperties.getAppName());
        executor.setIp(xxlJobProperties.getIp());
        executor.setPort(xxlJobProperties.getPort());
        executor.setAccessToken(xxlJobProperties.getAccessToken());
        executor.setLogPath(xxlJobProperties.getLogPath());
        executor.setLogRetentionDays(xxlJobProperties.getLogRetentionDays());
        return executor;
    }
}
