package com.quanxiaoha.xiaohashu.user.api;

import com.quanxiaoha.xiaohashu.user.constant.ApiConstants;
import com.quanxiaoha.xiaohashu.user.dto.req.FindUserByPhoneReqDTO;
import com.quanxiaoha.xiaohashu.user.dto.req.RegisterUserReqDTO;
import com.quanxiaoha.xiaohashu.user.dto.req.UpdateUserPasswordReqDTO;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UserFeignApiContractTest {

    @Test
    void registersUsersThroughTheUserServiceEndpoint() throws Exception {
        FeignClient client = UserFeignApi.class.getAnnotation(FeignClient.class);
        PostMapping mapping = UserFeignApi.class.getMethod("registerUser", RegisterUserReqDTO.class)
                .getAnnotation(PostMapping.class);
        RequestBody requestBody = UserFeignApi.class.getMethod("registerUser", RegisterUserReqDTO.class)
                .getParameters()[0].getAnnotation(RequestBody.class);

        assertNotNull(client);
        assertEquals(ApiConstants.SERVICE_NAME, client.name());
        assertEquals("/user/register", mapping.value()[0]);
        assertNotNull(requestBody);
    }

    @Test
    void findsUsersByPhoneThroughTheUserServiceEndpoint() throws Exception {
        FeignClient client = UserFeignApi.class.getAnnotation(FeignClient.class);
        PostMapping mapping = UserFeignApi.class.getMethod("findByPhone", FindUserByPhoneReqDTO.class)
                .getAnnotation(PostMapping.class);
        RequestBody requestBody = UserFeignApi.class.getMethod("findByPhone", FindUserByPhoneReqDTO.class)
                .getParameters()[0].getAnnotation(RequestBody.class);

        assertNotNull(client);
        assertEquals(ApiConstants.SERVICE_NAME, client.name());
        assertEquals("/user/findByPhone", mapping.value()[0]);
        assertNotNull(requestBody);
    }

    @Test
    void updatesPasswordsThroughTheUserServiceEndpoint() throws Exception {
        PostMapping mapping = UserFeignApi.class.getMethod("updatePassword", UpdateUserPasswordReqDTO.class)
                .getAnnotation(PostMapping.class);
        RequestBody requestBody = UserFeignApi.class.getMethod("updatePassword", UpdateUserPasswordReqDTO.class)
                .getParameters()[0].getAnnotation(RequestBody.class);

        assertEquals("/user/password/update", mapping.value()[0]);
        assertNotNull(requestBody);
    }
}
