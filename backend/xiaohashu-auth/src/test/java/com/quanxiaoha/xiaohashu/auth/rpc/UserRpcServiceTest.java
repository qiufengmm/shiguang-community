package com.quanxiaoha.xiaohashu.auth.rpc;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.user.api.UserFeignApi;
import com.quanxiaoha.xiaohashu.user.dto.req.RegisterUserReqDTO;
import com.quanxiaoha.xiaohashu.user.dto.req.FindUserByPhoneReqDTO;
import com.quanxiaoha.xiaohashu.user.dto.resp.FindUserByPhoneRspDTO;
import com.quanxiaoha.xiaohashu.user.dto.req.UpdateUserPasswordReqDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserRpcServiceTest {

    @Mock
    private UserFeignApi userFeignApi;

    @InjectMocks
    private UserRpcService userRpcService;

    @Test
    void returnsUserIdFromUserService() {
        when(userFeignApi.registerUser(any(RegisterUserReqDTO.class))).thenReturn(Response.success(99L));

        assertEquals(99L, userRpcService.registerUser("13800138000"));

        ArgumentCaptor<RegisterUserReqDTO> requestCaptor = ArgumentCaptor.forClass(RegisterUserReqDTO.class);
        verify(userFeignApi).registerUser(requestCaptor.capture());
        assertEquals("13800138000", requestCaptor.getValue().getPhone());
    }

    @Test
    void returnsNullWhenUserServiceReportsFailure() {
        when(userFeignApi.registerUser(any(RegisterUserReqDTO.class))).thenReturn(Response.fail());

        assertNull(userRpcService.registerUser("13800138000"));
    }

    @Test
    void findsUserByPhoneAndReturnsIdAndPassword() {
        FindUserByPhoneRspDTO user = FindUserByPhoneRspDTO.builder().id(17L).password("encoded").build();
        when(userFeignApi.findByPhone(any(FindUserByPhoneReqDTO.class))).thenReturn(Response.success(user));

        FindUserByPhoneRspDTO result = userRpcService.findUserByPhone("13800138000");

        assertEquals(17L, result.getId());
        assertEquals("encoded", result.getPassword());
        ArgumentCaptor<FindUserByPhoneReqDTO> requestCaptor = ArgumentCaptor.forClass(FindUserByPhoneReqDTO.class);
        verify(userFeignApi).findByPhone(requestCaptor.capture());
        assertEquals("13800138000", requestCaptor.getValue().getPhone());
    }

    @Test
    void returnsNullWhenPhoneLookupFails() {
        when(userFeignApi.findByPhone(any(FindUserByPhoneReqDTO.class))).thenReturn(Response.fail());

        assertNull(userRpcService.findUserByPhone("13800138000"));
    }

    @Test
    void sendsEncodedPasswordToUserService() {
        when(userFeignApi.updatePassword(any(UpdateUserPasswordReqDTO.class))).thenReturn(Response.success());

        org.junit.jupiter.api.Assertions.assertTrue(userRpcService.updatePassword("encoded-password"));

        ArgumentCaptor<UpdateUserPasswordReqDTO> requestCaptor =
                ArgumentCaptor.forClass(UpdateUserPasswordReqDTO.class);
        verify(userFeignApi).updatePassword(requestCaptor.capture());
        assertEquals("encoded-password", requestCaptor.getValue().getEncodePassword());
    }

    @Test
    void reportsPasswordUpdateFailureFromUserService() {
        when(userFeignApi.updatePassword(any(UpdateUserPasswordReqDTO.class))).thenReturn(Response.fail());

        org.junit.jupiter.api.Assertions.assertFalse(userRpcService.updatePassword("encoded-password"));
    }
}
