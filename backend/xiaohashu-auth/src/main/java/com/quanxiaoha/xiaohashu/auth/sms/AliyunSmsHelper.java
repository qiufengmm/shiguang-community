package com.quanxiaoha.xiaohashu.auth.sms;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.teautil.models.RuntimeOptions;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @description: 短信发送工具类
 **/
@Component
@Slf4j
public class AliyunSmsHelper {

    @Resource
    private Client client;

    /**
     * 发送短信
     * @param signName
     * @param templateCode
     * @param phone
     * @param templateParam
     * @return
     */
    public boolean sendMessage(String signName, String templateCode, String phone, String templateParam) {
        SendSmsVerifyCodeRequest sendSmsVerifyCodeRequest = new SendSmsVerifyCodeRequest()
                .setSignName(signName)
                .setTemplateCode(templateCode)
                .setPhoneNumber(phone)
                .setTemplateParam(templateParam);
        RuntimeOptions runtime = new RuntimeOptions();

        try {
            String maskedPhone = maskPhone(phone);
            log.info("==> 开始短信发送, phone: {}, signName: {}, templateCode: {}",
                    maskedPhone, signName, templateCode);

            // 发送短信
            SendSmsVerifyCodeResponse response = client.sendSmsVerifyCodeWithOptions(sendSmsVerifyCodeRequest, runtime);

            String responseCode = response == null || response.getBody() == null
                    ? "null" : response.getBody().getCode();
            log.info("==> 短信发送请求已返回, phone: {}, code: {}", maskedPhone, responseCode);

            // 注意：阿里云发送失败时也会返回 HTTP 200，必须判断 body 里的业务码，
            // 否则会把「余额不足 / 签名模板不合法」这类失败误判成发送成功
            if (response != null
                    && response.getBody() != null
                    && "OK".equals(response.getBody().getCode())) {
                return true;
            }

            log.error("==> 短信发送失败, phone: {}, code: {}", maskedPhone, responseCode);
            return false;
        } catch (Exception error) {
            log.error("==> 短信发送错误, phone: {}, errorType: {}", maskPhone(phone), error.getClass().getSimpleName());
            return false;
        }
    }

    private String maskPhone(String phone) {
        if (phone == null) return "***";
        String normalized = phone.replaceAll("\\s", "");
        if (normalized.length() <= 7) return "***";
        return normalized.substring(0, 3) + "****" + normalized.substring(normalized.length() - 4);
    }
}
