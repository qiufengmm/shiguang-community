package com.quanxiaoha.xiaohashu.oss.biz.config;

import io.minio.MinioClient;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Resource
    private MinioProperties minioProperties;

    @Bean
    @Lazy
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(minioProperties.getEndpoint())
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                .build();
    }
}
