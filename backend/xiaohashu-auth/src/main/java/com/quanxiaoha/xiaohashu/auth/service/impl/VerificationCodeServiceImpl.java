package com.quanxiaoha.xiaohashu.auth.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.auth.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.auth.enums.ResponseCodeEnum;
import com.quanxiaoha.xiaohashu.auth.model.vo.verificationcode.SendVerificationCodeReqVO;
import com.quanxiaoha.xiaohashu.auth.service.VerificationCodeService;
import com.quanxiaoha.xiaohashu.auth.sms.AliyunAccessKeyProperties;
import com.quanxiaoha.xiaohashu.auth.sms.AliyunSmsHelper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class VerificationCodeServiceImpl implements VerificationCodeService {

    private static final DefaultRedisScript<Long> COMPARE_AND_SET_SCRIPT = redisScript(
            "lua/verification_code_compare_and_set.lua");
    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE_SCRIPT = redisScript(
            "lua/verification_code_compare_and_delete.lua");

    private static DefaultRedisScript<Long> redisScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(Long.class);
        return script;
    }

    @Resource
    private RedisTemplate<String, Object> redisTemplate;
    @Resource
    private AliyunSmsHelper aliyunSmsHelper;
    @Resource
    private AliyunAccessKeyProperties aliyunAccessKeyProperties;

    /**
     * 发送短信验证码
     *
     * @param sendVerificationCodeReqVO
     * @return
     */
    @Override
    public Response<?> send(SendVerificationCodeReqVO sendVerificationCodeReqVO) {
        // 手机号
        String phone = sendVerificationCodeReqVO.getPhone();

        // 构建验证码 redis key
        String key = RedisKeyConstants.buildVerificationCodeKey(phone);

        // 原子占位，避免并发请求同时给同一手机号发送验证码。
        String reservation = "PENDING:" + UUID.randomUUID();
        Boolean reserved = redisTemplate.opsForValue().setIfAbsent(key, reservation, 3, TimeUnit.MINUTES);
        if (!Boolean.TRUE.equals(reserved)) {
            // 若之前发送的验证码未过期，则提示发送频繁
            throw new BizException(ResponseCodeEnum.VERIFICATION_CODE_SEND_FREQUENTLY);
        }

        // 生成 6 位随机数字验证码
        String verificationCode = RandomUtil.randomNumbers(6);

        log.info("==> 已为手机号尾号 {} 生成验证码", phone == null || phone.length() < 4
                ? "****" : "****" + phone.substring(phone.length() - 4));

        // 同步确认第三方实际接受了短信，否则不能把未发送的验证码留在 Redis。
        boolean sent;
        try {
            String signName = aliyunAccessKeyProperties.getSmsSignName();
            String templateCode = aliyunAccessKeyProperties.getSmsTemplateCode();
            // 号码认证服务-短信认证 赠送模板的变量：code 为验证码，min 为有效分钟数
            String templateParam = String.format("{\"code\":\"%s\",\"min\":\"3\"}", verificationCode);
            sent = aliyunSmsHelper.sendMessage(signName, templateCode, phone, templateParam);
        } catch (Exception e) {
            releaseReservation(key, reservation);
            log.error("==> 短信验证码发送异常, phone: {}", maskPhone(phone), e);
            throw new BizException(ResponseCodeEnum.VERIFICATION_CODE_SEND_FAIL);
        }

        if (!sent) {
            releaseReservation(key, reservation);
            throw new BizException(ResponseCodeEnum.VERIFICATION_CODE_SEND_FAIL);
        }

        // 仅当占位仍属于本次请求时，才将已发送验证码写入 Redis，TTL 从发送成功后开始计时。
        Long stored = redisTemplate.execute(COMPARE_AND_SET_SCRIPT, List.of(key), reservation, verificationCode, 180);
        if (!Long.valueOf(1L).equals(stored)) {
            throw new BizException(ResponseCodeEnum.VERIFICATION_CODE_SEND_FAIL);
        }

        return Response.success();
    }

    private void releaseReservation(String key, String reservation) {
        try {
            redisTemplate.execute(COMPARE_AND_DELETE_SCRIPT, List.of(key), reservation);
        } catch (Exception e) {
            log.error("==> 清理短信发送占位失败", e);
        }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "****";
        return "****" + phone.substring(phone.length() - 4);
    }
}
