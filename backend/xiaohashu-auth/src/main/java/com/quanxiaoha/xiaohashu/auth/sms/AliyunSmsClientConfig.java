package com.quanxiaoha.xiaohashu.auth.sms;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.teaopenapi.models.Config;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @description: 短信发送客户端（号码认证服务 Dypnsapi）
 **/
@Configuration
@Slf4j
public class AliyunSmsClientConfig {

    @Resource
    private AliyunAccessKeyProperties aliyunAccessKeyProperties;

    @Bean
    public Client smsClient() {
        try {
            Config config = new Config()
                    // 必填
                    .setAccessKeyId(aliyunAccessKeyProperties.getAccessKeyId())
                    // 必填
                    .setAccessKeySecret(aliyunAccessKeyProperties.getAccessKeySecret());

            // 号码认证服务的 Endpoint
            config.endpoint = "dypnsapi.aliyuncs.com";

            return new Client(config);
        } catch (Exception e) {
            log.error("初始化阿里云短信发送客户端错误: ", e);
            return null;
        }
    }
}
