package com.quanxiaoha.xiaohashu.auth.service.impl;

import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.xiaohashu.auth.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.auth.enums.ResponseCodeEnum;
import com.quanxiaoha.xiaohashu.auth.model.vo.verificationcode.SendVerificationCodeReqVO;
import com.quanxiaoha.xiaohashu.auth.sms.AliyunAccessKeyProperties;
import com.quanxiaoha.xiaohashu.auth.sms.AliyunSmsHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerificationCodeServiceImplTest {

    private static final String PHONE = "13800138000";

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOperations;
    @Mock
    private AliyunSmsHelper aliyunSmsHelper;
    @Mock
    private AliyunAccessKeyProperties aliyunAccessKeyProperties;
    @InjectMocks
    private VerificationCodeServiceImpl service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void storesOtpAfterSmsProviderAcceptsAndReservationStillBelongsToRequest() {
        String key = RedisKeyConstants.buildVerificationCodeKey(PHONE);
        when(aliyunAccessKeyProperties.getSmsSignName()).thenReturn("test-sign");
        when(aliyunAccessKeyProperties.getSmsTemplateCode()).thenReturn("test-template");
        when(valueOperations.setIfAbsent(eq(key), anyString(), eq(3L), eq(TimeUnit.MINUTES))).thenReturn(true);
        when(aliyunSmsHelper.sendMessage(eq("test-sign"), eq("test-template"), eq(PHONE), anyString()))
                .thenReturn(true);
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of(key)), anyString(), anyString(), eq(180)))
                .thenReturn(1L);

        assertTrue(service.send(SendVerificationCodeReqVO.builder().phone(PHONE).build()).isSuccess());

        org.mockito.ArgumentCaptor<String> reservation = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> code = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).execute(any(DefaultRedisScript.class), eq(List.of(key)), reservation.capture(),
                code.capture(), eq(180));
        assertTrue(reservation.getValue().startsWith("PENDING:"));
        assertTrue(code.getValue().matches("\\d{6}"));
    }

    @Test
    void removesReservationAndReturnsFailureWhenSmsProviderRejectsRequest() {
        String key = RedisKeyConstants.buildVerificationCodeKey(PHONE);
        when(aliyunAccessKeyProperties.getSmsSignName()).thenReturn("test-sign");
        when(aliyunAccessKeyProperties.getSmsTemplateCode()).thenReturn("test-template");
        when(valueOperations.setIfAbsent(eq(key), anyString(), eq(3L), eq(TimeUnit.MINUTES))).thenReturn(true);
        when(aliyunSmsHelper.sendMessage(eq("test-sign"), eq("test-template"), eq(PHONE), anyString()))
                .thenReturn(false);
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of(key)),
                org.mockito.ArgumentMatchers.<Object>argThat(value -> value instanceof String text
                        && text.startsWith("PENDING:")))).thenReturn(1L);

        BizException exception = assertThrows(BizException.class,
                () -> service.send(SendVerificationCodeReqVO.builder().phone(PHONE).build()));

        assertEquals(ResponseCodeEnum.VERIFICATION_CODE_SEND_FAIL.getErrorCode(), exception.getErrorCode());
        verify(redisTemplate).execute(any(DefaultRedisScript.class), eq(List.of(key)),
                org.mockito.ArgumentMatchers.<Object>argThat(value -> value instanceof String text
                        && text.startsWith("PENDING:")));
    }

    @Test
    void doesNotCallSmsProviderWhenAnotherRequestOwnsTheReservation() {
        String key = RedisKeyConstants.buildVerificationCodeKey(PHONE);
        when(valueOperations.setIfAbsent(eq(key), anyString(), eq(3L), eq(TimeUnit.MINUTES))).thenReturn(false);

        BizException exception = assertThrows(BizException.class,
                () -> service.send(SendVerificationCodeReqVO.builder().phone(PHONE).build()));

        assertEquals(ResponseCodeEnum.VERIFICATION_CODE_SEND_FREQUENTLY.getErrorCode(), exception.getErrorCode());
        verify(aliyunSmsHelper, never()).sendMessage(anyString(), anyString(), anyString(), anyString());
    }
}
