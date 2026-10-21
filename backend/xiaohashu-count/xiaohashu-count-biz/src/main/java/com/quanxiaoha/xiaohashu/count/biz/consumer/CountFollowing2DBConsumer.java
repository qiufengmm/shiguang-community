package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.count.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.count.biz.model.dto.CountFollowUnfollowMqDTO;
import com.quanxiaoha.xiaohashu.count.biz.service.CountSourceRefreshService;
import com.google.common.util.concurrent.RateLimiter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/** Refreshes any legacy queued following delta from authoritative source rows. */
@Component
@RocketMQMessageListener(consumerGroup = "xiaohashu_group_" + MQConstants.TOPIC_COUNT_FOLLOWING_2_DB,
        topic = MQConstants.TOPIC_COUNT_FOLLOWING_2_DB)
@Slf4j
public class CountFollowing2DBConsumer implements RocketMQListener<String> {

    @Resource
    private CountSourceRefreshService countSourceRefreshService;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    private final RateLimiter rateLimiter = RateLimiter.create(5000);

    @Override
    public void onMessage(String body) {
        rateLimiter.acquire();
        if (StringUtils.isBlank(body)) throw new IllegalArgumentException("Empty legacy following refresh event");
        CountFollowUnfollowMqDTO dto = JsonUtils.parseObject(body, CountFollowUnfollowMqDTO.class);
        if (dto == null || dto.getUserId() == null || dto.getUserId() <= 0) {
            throw new IllegalArgumentException("Invalid legacy following refresh event");
        }
        countSourceRefreshService.refreshFollowing(dto.getUserId());
        redisTemplate.delete(RedisKeyConstants.buildCountUserKey(dto.getUserId()));
    }
}
