package com.quanxiaoha.xiaohashu.oss.biz.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.CredentialsProviderFactory;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class AliyunOSSConfig {

    @Bean(destroyMethod = "shutdown")
    @Lazy
    public OSS aliyunOSSClient(AliyunOSSProperties properties) {
        if (StringUtils.isAnyBlank(properties.getEndpoint(), properties.getAccessKey(), properties.getSecretKey())) {
            throw new IllegalStateException("使用阿里云 OSS 前，请配置 ALIYUN_OSS_ENDPOINT、ALIYUN_ACCESS_KEY_ID 和 ALIYUN_ACCESS_KEY_SECRET");
        }

        String endpoint = properties.getEndpoint().trim();
        if (!StringUtils.startsWithAny(endpoint, "http://", "https://")) {
            endpoint = "https://" + endpoint;
        }

        DefaultCredentialProvider credentialsProvider = CredentialsProviderFactory.newDefaultCredentialProvider(
                properties.getAccessKey(), properties.getSecretKey());
        return new OSSClientBuilder().build(endpoint, credentialsProvider);
    }
}
