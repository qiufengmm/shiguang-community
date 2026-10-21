package com.quanxiaoha.xiaohashu.note.biz.consumer;

import com.quanxiaoha.xiaohashu.note.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.note.biz.constant.RedisKeyConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 延时删除 Redis 已发布笔记列表缓存
 */
@Component
@Slf4j
@RocketMQMessageListener(
        consumerGroup = "xiaohashu_group_" + MQConstants.TOPIC_DELAY_DELETE_PUBLISHED_NOTE_LIST_REDIS_CACHE,
        topic = MQConstants.TOPIC_DELAY_DELETE_PUBLISHED_NOTE_LIST_REDIS_CACHE)
public class DelayDeletePublishedNoteListRedisCacheConsumer implements RocketMQListener<String> {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public void onMessage(String body) {
        Long userId = Long.valueOf(body);
        String publishedNoteListRedisKey = RedisKeyConstants.buildPublishedNoteListKey(userId);
        redisTemplate.delete(publishedNoteListRedisKey);
        log.info("## 延时删除 Redis 已发布笔记列表缓存成功, userId: {}", userId);
    }
}
