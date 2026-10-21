package com.quanxiaoha.xiaohashu.oss.biz.strategy.impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.quanxiaoha.xiaohashu.oss.biz.config.AliyunOSSProperties;
import com.quanxiaoha.xiaohashu.oss.biz.strategy.FileStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class AliyunOSSFileStrategy implements FileStrategy {

    private final AliyunOSSProperties properties;
    private final ObjectProvider<OSS> ossClientProvider;

    @Override
    public String uploadFile(MultipartFile file) {
        log.info("## 上传文件至阿里云 OSS ...");
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件大小不能为空");
        }
        if (StringUtils.isAnyBlank(properties.getEndpoint(), properties.getAccessKey(), properties.getSecretKey(), properties.getBucketName())) {
            throw new IllegalStateException("阿里云 OSS 配置不完整，请检查 Endpoint、AccessKey 和 Bucket 名称");
        }

        String objectName = UUID.randomUUID().toString().replace("-", "") + getFileSuffix(file.getOriginalFilename());
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        if (StringUtils.isNotBlank(file.getContentType())) {
            metadata.setContentType(file.getContentType());
        }

        log.info("==> 开始上传文件至阿里云 OSS, ObjectName: {}", objectName);
        try (InputStream inputStream = file.getInputStream()) {
            ossClientProvider.getObject().putObject(properties.getBucketName(), objectName, inputStream, metadata);
        } catch (IOException e) {
            throw new IllegalStateException("读取上传文件失败", e);
        }

        String endpoint = properties.getEndpoint().trim();
        if (!StringUtils.startsWithAny(endpoint, "http://", "https://")) {
            endpoint = "https://" + endpoint;
        }
        URI endpointUri = URI.create(endpoint);
        if (StringUtils.isBlank(endpointUri.getHost())) {
            throw new IllegalStateException("ALIYUN_OSS_ENDPOINT 格式无效");
        }
        String url = String.format("%s://%s.%s/%s", endpointUri.getScheme(), properties.getBucketName(), endpointUri.getHost(), objectName);
        log.info("==> 上传文件至阿里云 OSS 成功，访问路径: {}", url);
        return url;
    }

    private String getFileSuffix(String originalFileName) {
        if (StringUtils.isBlank(originalFileName)) {
            return "";
        }
        int dotIndex = originalFileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == originalFileName.length() - 1) {
            return "";
        }
        String suffix = originalFileName.substring(dotIndex);
        return suffix.matches("\\.[A-Za-z0-9]{1,10}") ? suffix : "";
    }
}
