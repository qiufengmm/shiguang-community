package com.quanxiaoha.xiaohashu.oss.api;

import com.quanxiaoha.xiaohashu.oss.constant.ApiConstants;
import com.quanxiaoha.xiaohashu.oss.config.FeignFormConfig;
import feign.form.spring.SpringFormEncoder;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class FileFeignApiContractTest {

    @Test
    void targetsOssServiceAndMapsMultipartUploadEndpoint() throws Exception {
        FeignClient client = FileFeignApi.class.getAnnotation(FeignClient.class);
        PostMapping mapping = FileFeignApi.class.getMethod("uploadFile", MultipartFile.class)
                .getAnnotation(PostMapping.class);
        RequestPart part = FileFeignApi.class.getMethod("uploadFile", MultipartFile.class)
                .getParameters()[0].getAnnotation(RequestPart.class);

        assertEquals(ApiConstants.SERVICE_NAME, client.name());
        assertEquals("xiaohashu-oss", client.name());
        assertEquals(FeignFormConfig.class, client.configuration()[0]);
        assertEquals("/file/upload", mapping.value()[0]);
        assertEquals(MediaType.MULTIPART_FORM_DATA_VALUE, mapping.consumes()[0]);
        assertEquals("file", part.value());
    }

    @Test
    void createsSpringMultipartEncoder() {
        assertInstanceOf(SpringFormEncoder.class, new FeignFormConfig().feignFormEncoder());
    }
}
