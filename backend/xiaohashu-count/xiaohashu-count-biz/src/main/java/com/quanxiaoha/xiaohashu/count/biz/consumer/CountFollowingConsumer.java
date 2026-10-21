package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.count.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.count.biz.model.dto.CountFollowUnfollowMqDTO;
import com.quanxiaoha.xiaohashu.count.biz.service.CountSourceRefreshService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/** Recalculates the following total from committed relation rows. */
@Component
@RocketMQMessageListener(consumerGroup = "xiaohashu_group_" + MQConstants.TOPIC_COUNT_FOLLOWING,
        topic = MQConstants.TOPIC_COUNT_FOLLOWING)
@Slf4j
public class CountFollowingConsumer implements RocketMQListener<String> {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private CountSourceRefreshService countSourceRefreshService;

    @Override
    public void onMessage(String body) {
        if (StringUtils.isBlank(body)) throw new IllegalArgumentException("Empty following count event");
        CountFollowUnfollowMqDTO dto = JsonUtils.parseObject(body, CountFollowUnfollowMqDTO.class);
        if (dto == null || dto.getUserId() == null || dto.getUserId() <= 0) {
            throw new IllegalArgumentException("Invalid following count event");
        }

        countSourceRefreshService.refreshFollowing(dto.getUserId());
        redisTemplate.delete(RedisKeyConstants.buildCountUserKey(dto.getUserId()));
        log.info("Refreshed following total from source rows for userId={}", dto.getUserId());
    }
}
