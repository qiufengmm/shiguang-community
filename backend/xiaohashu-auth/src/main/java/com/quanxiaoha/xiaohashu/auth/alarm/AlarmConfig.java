package com.quanxiaoha.xiaohashu.auth.alarm;

import com.quanxiaoha.xiaohashu.auth.alarm.impl.MailAlarmHelper;
import com.quanxiaoha.xiaohashu.auth.alarm.impl.SmsAlarmHelper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 告警配置类
 * <p>
 * 根据 Nacos 配置中的 alarm.type（sms / mail），动态决定容器中注入哪个告警实现类。
 * 加 @RefreshScope 后，Nacos 上改了 alarm.type 并发布，无需重启即可切换实现类。
 */
@Configuration
@RefreshScope
public class AlarmConfig {

    @Value("${alarm.type}")
    private String alarmType;

    @Bean
    @RefreshScope
    public AlarmInterface alarmHelper() {
        // 根据配置中的告警类型，初始化选择不同的告警实现类
        if (StringUtils.equals("sms", alarmType)) {
            return new SmsAlarmHelper();
        } else if (StringUtils.equals("mail", alarmType)) {
            return new MailAlarmHelper();
        } else {
            throw new IllegalArgumentException("错误的告警类型...");
        }
    }

}
