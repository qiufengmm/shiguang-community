package com.quanxiaoha.xiaohashu.oss.biz.service.impl;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.oss.biz.service.FileService;
import com.quanxiaoha.xiaohashu.oss.biz.strategy.FileStrategy;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileServiceImpl implements FileService {

    @Resource
    private FileStrategy fileStrategy;

    @Override
    public Response<?> uploadFile(MultipartFile file) {
        String url = fileStrategy.uploadFile(file);
        return Response.success(url);
    }
}
