package com.quanxiaoha.xiaohashu.user.biz.rpc;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.oss.api.FileFeignApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OssRpcServiceTest {

    @Mock
    private FileFeignApi fileFeignApi;

    @InjectMocks
    private OssRpcService ossRpcService;

    @Test
    void returnsUploadedFileUrlWhenOssSucceeds() {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[]{1});
        Response<?> response = Response.success("https://oss.example/avatar.png");
        doReturn(response).when(fileFeignApi).uploadFile(file);

        assertEquals("https://oss.example/avatar.png", ossRpcService.uploadFile(file));
        verify(fileFeignApi).uploadFile(file);
    }

    @Test
    void returnsNullWhenOssReportsFailure() {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[]{1});
        doReturn(Response.fail()).when(fileFeignApi).uploadFile(file);

        assertNull(ossRpcService.uploadFile(file));
    }
}
