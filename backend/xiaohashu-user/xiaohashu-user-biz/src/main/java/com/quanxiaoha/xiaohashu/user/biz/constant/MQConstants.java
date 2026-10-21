package com.quanxiaoha.xiaohashu.user.biz.constant;

/**
 * 用户服务 RocketMQ 主题常量。
 */
public interface MQConstants {

    /** 延迟双删用户 Redis 缓存。 */
    String TOPIC_DELAY_DELETE_USER_REDIS_CACHE = "DelayDeleteUserRedisCacheTopic";
}
