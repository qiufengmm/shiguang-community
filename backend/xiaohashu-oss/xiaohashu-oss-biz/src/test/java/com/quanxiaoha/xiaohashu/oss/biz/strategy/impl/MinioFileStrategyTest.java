package com.quanxiaoha.xiaohashu.oss.biz.strategy.impl;

import com.quanxiaoha.xiaohashu.oss.biz.config.MinioProperties;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MinioFileStrategyTest {

    @Test
    void uploadReturnsConfiguredSameOriginMediaUrl() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        when(minioClient.putObject(any())).thenReturn(null);

        MinioProperties properties = new MinioProperties();
        properties.setEndpoint("http://minio:9000");
        properties.setBucketName("xiaohashu-local");
        properties.setPublicBaseUrl("/media/xiaohashu-local");

        MinioFileStrategy strategy = new MinioFileStrategy(minioClient, properties);
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", new byte[]{1, 2, 3});

        String url = strategy.uploadFile(file);

        org.junit.jupiter.api.Assertions.assertTrue(
                url.matches("^/media/xiaohashu-local/[0-9a-f]{32}\\.png$"), url);
    }
}
