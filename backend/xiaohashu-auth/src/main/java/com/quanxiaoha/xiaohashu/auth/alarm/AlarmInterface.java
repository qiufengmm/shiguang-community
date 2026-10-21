package com.quanxiaoha.xiaohashu.auth.alarm;

/**
 * 告警接口
 */
public interface AlarmInterface {

    /**
     * 发送告警信息
     *
     * @param message 告警内容
     * @return 是否发送成功
     */
    boolean send(String message);

}
