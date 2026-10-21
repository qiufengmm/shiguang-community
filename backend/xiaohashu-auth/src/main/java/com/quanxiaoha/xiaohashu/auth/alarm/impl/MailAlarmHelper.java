package com.quanxiaoha.xiaohashu.auth.alarm.impl;

import com.quanxiaoha.xiaohashu.auth.alarm.AlarmInterface;
import lombok.extern.slf4j.Slf4j;

/**
 * 邮件告警
 */
@Slf4j
public class MailAlarmHelper implements AlarmInterface {

    /**
     * 发送告警信息
     *
     * @param message 告警内容
     * @return 是否发送成功
     */
    @Override
    public boolean send(String message) {
        log.info("==> 【邮件告警】：{}", message);

        // 业务逻辑...

        return true;
    }

}
