package com.quanxiaoha.xiaohashu.auth.service.impl;

import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.xiaohashu.auth.enums.ResponseCodeEnum;
import com.quanxiaoha.xiaohashu.auth.model.vo.user.UpdatePasswordReqVO;
import com.quanxiaoha.xiaohashu.auth.model.vo.user.UserLoginReqVO;
import com.quanxiaoha.xiaohashu.auth.rpc.UserRpcService;
import com.quanxiaoha.xiaohashu.user.dto.resp.FindUserByPhoneRspDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private UserRpcService userRpcService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @InjectMocks
    private AuthServiceImpl authService;

    @BeforeEach
    void setCurrentUserId() {
        LoginUserContextHolder.setUserId(42L);
    }

    @AfterEach
    void clearCurrentUserId() {
        LoginUserContextHolder.remove();
    }

    @Test
    void passwordLoginReadsCredentialFromUserServiceBeforeMatching() {
        UserLoginReqVO request = UserLoginReqVO.builder()
                .phone("13800138000")
                .password("plain")
                .type(2)
                .build();
        when(userRpcService.findUserByPhone(request.getPhone()))
                .thenReturn(FindUserByPhoneRspDTO.builder().id(17L).password("encoded").build());
        when(passwordEncoder.matches("plain", "encoded")).thenReturn(false);

        BizException exception = assertThrows(BizException.class, () -> authService.loginAndRegister(request));

        assertEquals(ResponseCodeEnum.PHONE_OR_PASSWORD_ERROR.getErrorCode(), exception.getErrorCode());
        verify(userRpcService).findUserByPhone("13800138000");
        verify(passwordEncoder).matches("plain", "encoded");
    }

    @Test
    void passwordLoginReportsMissingUserFromUserService() {
        UserLoginReqVO request = UserLoginReqVO.builder()
                .phone("13800138000")
                .password("plain")
                .type(2)
                .build();
        when(userRpcService.findUserByPhone(request.getPhone())).thenReturn(null);

        BizException exception = assertThrows(BizException.class, () -> authService.loginAndRegister(request));

        assertEquals(ResponseCodeEnum.USER_NOT_FOUND.getErrorCode(), exception.getErrorCode());
    }

    @Test
    void passwordUpdateDelegatesEncodedPasswordToUserService() {
        when(passwordEncoder.encode("plain")).thenReturn("encoded");
        when(userRpcService.updatePassword("encoded")).thenReturn(true);
        UpdatePasswordReqVO request = UpdatePasswordReqVO.builder().newPassword("plain").build();

        assertTrue(authService.updatePassword(request).isSuccess());

        verify(passwordEncoder).encode("plain");
        verify(userRpcService).updatePassword("encoded");
    }

    @Test
    void passwordUpdateFailsWhenUserServiceRejectsIt() {
        when(passwordEncoder.encode("plain")).thenReturn("encoded");
        when(userRpcService.updatePassword("encoded")).thenReturn(false);
        UpdatePasswordReqVO request = UpdatePasswordReqVO.builder().newPassword("plain").build();

        BizException exception = assertThrows(BizException.class, () -> authService.updatePassword(request));

        assertEquals(ResponseCodeEnum.PASSWORD_UPDATE_FAIL.getErrorCode(), exception.getErrorCode());
    }

    @Test
    void rejectsVerificationCodeAlreadyConsumedByAnotherConcurrentLogin() {
        String phone = "13800138000";
        String key = "verification_code:" + phone;
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(key)).thenReturn("123456");
        when(userRpcService.findUserByPhone(phone)).thenReturn(FindUserByPhoneRspDTO.builder().id(17L).build());
        when(redisTemplate.execute(org.mockito.ArgumentMatchers.any(DefaultRedisScript.class),
                org.mockito.ArgumentMatchers.eq(List.of(key)), org.mockito.ArgumentMatchers.eq("123456")))
                .thenReturn(0L);
        UserLoginReqVO request = UserLoginReqVO.builder().phone(phone).code("123456").type(1).build();

        BizException exception = assertThrows(BizException.class, () -> authService.loginAndRegister(request));

        assertEquals(ResponseCodeEnum.VERIFICATION_CODE_ERROR.getErrorCode(), exception.getErrorCode());
        verify(redisTemplate).execute(org.mockito.ArgumentMatchers.any(DefaultRedisScript.class),
                org.mockito.ArgumentMatchers.eq(List.of(key)), org.mockito.ArgumentMatchers.eq("123456"));
    }

    @Test
    void wrongVerificationCodeIsNotConsumedOrSentToUserService() {
        String phone = "13800138000";
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get("verification_code:" + phone)).thenReturn("654321");
        UserLoginReqVO request = UserLoginReqVO.builder().phone(phone).code("123456").type(1).build();

        BizException exception = assertThrows(BizException.class, () -> authService.loginAndRegister(request));

        assertEquals(ResponseCodeEnum.VERIFICATION_CODE_ERROR.getErrorCode(), exception.getErrorCode());
        verify(redisTemplate, never()).execute(org.mockito.ArgumentMatchers.any(DefaultRedisScript.class),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(Object[].class));
        verify(userRpcService, never()).findUserByPhone(phone);
    }
}
