package com.quanxiaoha.xiaohashu.oss.biz.strategy;

import org.springframework.web.multipart.MultipartFile;

public interface FileStrategy {
    String uploadFile(MultipartFile file);
}
