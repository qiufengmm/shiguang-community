package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.count.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.count.biz.model.dto.NoteOperateMqDTO;
import com.quanxiaoha.xiaohashu.count.biz.service.CountNotePublishService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 计数：用户笔记发布数
 */
@Component
@RocketMQMessageListener(consumerGroup = "xiaohashu_group_" + MQConstants.TOPIC_NOTE_OPERATE,
        topic = MQConstants.TOPIC_NOTE_OPERATE)
@Slf4j
public class CountNotePublishConsumer implements RocketMQListener<Message> {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private CountNotePublishService countNotePublishService;

    @Override
    public void onMessage(Message message) {
        String bodyJsonStr = new String(message.getBody());
        String tags = message.getTags();
        log.info("==> CountNotePublishConsumer 消费了消息 {}, tags: {}", bodyJsonStr, tags);

        if (Objects.equals(tags, MQConstants.TAG_NOTE_PUBLISH)) {
            handleTagMessage(bodyJsonStr, 1L);
        } else if (Objects.equals(tags, MQConstants.TAG_NOTE_DELETE)) {
            handleTagMessage(bodyJsonStr, -1L);
        }
    }

    private void handleTagMessage(String bodyJsonStr, long delta) {
        NoteOperateMqDTO dto = JsonUtils.parseObject(bodyJsonStr, NoteOperateMqDTO.class);
        if (Objects.isNull(dto) || Objects.isNull(dto.getCreatorId()) || Objects.isNull(dto.getNoteId())) return;

        Long creatorId = dto.getCreatorId();
        String countUserRedisKey = RedisKeyConstants.buildCountUserKey(creatorId);
        countNotePublishService.apply(dto, delta);
        // Invalidate on both first delivery and duplicate delivery. If Redis fails,
        // RocketMQ retries; the durable event key keeps MySQL idempotent.
        redisTemplate.delete(countUserRedisKey);
    }
}
