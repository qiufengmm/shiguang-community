package com.quanxiaoha.xiaohashu.oss.biz.strategy.impl;

import com.quanxiaoha.xiaohashu.oss.biz.config.MinioProperties;
import com.quanxiaoha.xiaohashu.oss.biz.strategy.FileStrategy;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Slf4j
public class MinioFileStrategy implements FileStrategy {

    private final MinioProperties minioProperties;
    private final MinioClient minioClient;

    public MinioFileStrategy(MinioClient minioClient, MinioProperties minioProperties) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
    }

    @Override
    @SneakyThrows
    public String uploadFile(MultipartFile file) {
        log.info("## 上传文件至 Minio ...");

        if (file == null || file.getSize() == 0) {
            log.error("==> 上传文件异常：文件大小为空 ...");
            throw new RuntimeException("文件大小不能为空");
        }

        String originalFileName = file.getOriginalFilename();
        String contentType = file.getContentType();
        String key = UUID.randomUUID().toString().replace("-", "");
        String suffix = getFileSuffix(originalFileName);
        String objectName = String.format("%s%s", key, suffix);
        String bucketName = minioProperties.getBucketName();
        if (StringUtils.isBlank(bucketName)) {
            throw new IllegalStateException("MinIO Bucket 名称未配置");
        }

        log.info("==> 开始上传文件至 Minio, ObjectName: {}", objectName);
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucketName)
                .object(objectName)
                .stream(file.getInputStream(), file.getSize(), -1)
                .contentType(contentType)
                .build());

        String publicBaseUrl = minioProperties.getPublicBaseUrl();
        String url = StringUtils.isNotBlank(publicBaseUrl)
                ? String.format("%s/%s", StringUtils.removeEnd(publicBaseUrl, "/"), objectName)
                : String.format("%s/%s/%s", minioProperties.getEndpoint(), bucketName, objectName);
        log.info("==> 上传文件至 Minio 成功，访问路径: {}", url);
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
