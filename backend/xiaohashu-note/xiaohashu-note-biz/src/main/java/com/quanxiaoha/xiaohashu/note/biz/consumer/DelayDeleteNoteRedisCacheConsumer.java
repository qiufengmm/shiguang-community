package com.quanxiaoha.xiaohashu.note.biz.consumer;

import com.quanxiaoha.xiaohashu.note.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.note.biz.constant.RedisKeyConstants;
import com.quanxiaoha.framework.common.util.JsonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 延时删除 Redis 笔记缓存
 **/
@Component
@Slf4j
@RocketMQMessageListener(consumerGroup = "xiaohashu_group_" + MQConstants.TOPIC_DELAY_DELETE_NOTE_REDIS_CACHE, // Group
        topic = MQConstants.TOPIC_DELAY_DELETE_NOTE_REDIS_CACHE // 消费的主题 Topic
)
public class DelayDeleteNoteRedisCacheConsumer implements RocketMQListener<String> {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public void onMessage(String body) {
        try {
            List<Long> noteIdAndUserId = JsonUtils.parseList(body, Long.class);
            Long noteId = noteIdAndUserId.get(0);
            Long userId = noteIdAndUserId.get(1);
            log.info("## 延迟消息消费成功, noteId: {}, userId: {}", noteId, userId);

            // 删除 Redis 笔记详情和个人主页笔记列表缓存
            String noteDetailRedisKey = RedisKeyConstants.buildNoteDetailKey(noteId);
            String publishedNoteListRedisKey = RedisKeyConstants.buildPublishedNoteListKey(userId);
            redisTemplate.delete(Arrays.asList(noteDetailRedisKey, publishedNoteListRedisKey));
        } catch (Exception e) {
            log.error("## 延迟删除 Redis 笔记缓存失败", e);
            // 让 RocketMQ 感知消费失败并按重试策略重新投递，避免异常被吞后消息直接确认。
            throw new IllegalStateException("Failed to delete delayed note caches; message should be retried", e);
        }
    }
}
