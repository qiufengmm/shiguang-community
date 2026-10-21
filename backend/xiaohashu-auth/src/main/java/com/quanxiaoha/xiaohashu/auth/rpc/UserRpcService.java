package com.quanxiaoha.xiaohashu.auth.rpc;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.user.api.UserFeignApi;
import com.quanxiaoha.xiaohashu.user.dto.req.RegisterUserReqDTO;
import com.quanxiaoha.xiaohashu.user.dto.req.FindUserByPhoneReqDTO;
import com.quanxiaoha.xiaohashu.user.dto.req.UpdateUserPasswordReqDTO;
import com.quanxiaoha.xiaohashu.user.dto.resp.FindUserByPhoneRspDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 用户服务 RPC 调用。
 */
@Component
public class UserRpcService {

    @Resource
    private UserFeignApi userFeignApi;

    public Long registerUser(String phone) {
        RegisterUserReqDTO request = RegisterUserReqDTO.builder().phone(phone).build();
        Response<Long> response = userFeignApi.registerUser(request);
        if (response == null || !response.isSuccess()) {
            return null;
        }
        return response.getData();
    }

    public FindUserByPhoneRspDTO findUserByPhone(String phone) {
        FindUserByPhoneReqDTO request = FindUserByPhoneReqDTO.builder().phone(phone).build();
        Response<FindUserByPhoneRspDTO> response = userFeignApi.findByPhone(request);
        if (response == null || !response.isSuccess()) {
            return null;
        }
        return response.getData();
    }

    public boolean updatePassword(String encodePassword) {
        UpdateUserPasswordReqDTO request = UpdateUserPasswordReqDTO.builder()
                .encodePassword(encodePassword)
                .build();
        Response<?> response = userFeignApi.updatePassword(request);
        return response != null && response.isSuccess();
    }
}
