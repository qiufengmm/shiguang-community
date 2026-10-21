package com.quanxiaoha.xiaohashu.auth.sms;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "aliyun")
@Component
@Data
public class AliyunAccessKeyProperties {
    private String accessKeyId;
    private String accessKeySecret;
    /**
     * 短信签名（号码认证服务-短信认证 赠送）
     */
    private String smsSignName;
    /**
     * 短信模板 CODE（号码认证服务-短信认证 赠送）
     */
    private String smsTemplateCode;
}
