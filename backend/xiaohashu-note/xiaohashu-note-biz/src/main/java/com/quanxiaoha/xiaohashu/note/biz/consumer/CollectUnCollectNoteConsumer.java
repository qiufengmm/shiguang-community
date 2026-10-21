package com.quanxiaoha.xiaohashu.note.biz.consumer;

import com.google.common.util.concurrent.RateLimiter;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.note.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteCollectionDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteCollectionDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.CollectUnCollectNoteMqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 笔记收藏、取消收藏 MQ 消费者
 */
@Component
@RocketMQMessageListener(consumerGroup = "xiaohashu_group_" + MQConstants.TOPIC_COLLECT_OR_UN_COLLECT,
        topic = MQConstants.TOPIC_COLLECT_OR_UN_COLLECT,
        consumeMode = ConsumeMode.ORDERLY)
@Slf4j
public class CollectUnCollectNoteConsumer implements RocketMQListener<Message> {

    @Resource
    private NoteCollectionDOMapper noteCollectionDOMapper;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    private final RateLimiter rateLimiter = RateLimiter.create(5000);

    @Override
    public void onMessage(Message message) {
        rateLimiter.acquire();

        String bodyJsonStr = new String(message.getBody());
        String tags = message.getTags();
        log.info("==> CollectUnCollectNoteConsumer 消费了消息 {}, tags: {}", bodyJsonStr, tags);

        if (Objects.equals(tags, MQConstants.TAG_COLLECT)) {
            handleCollectNoteTagMessage(bodyJsonStr);
        } else if (Objects.equals(tags, MQConstants.TAG_UN_COLLECT)) {
            handleUnCollectNoteTagMessage(bodyJsonStr);
        }
    }

    private void handleCollectNoteTagMessage(String bodyJsonStr) {
        CollectUnCollectNoteMqDTO dto = JsonUtils.parseObject(bodyJsonStr, CollectUnCollectNoteMqDTO.class);
        if (Objects.isNull(dto)) return;

        NoteCollectionDO noteCollectionDO = NoteCollectionDO.builder()
                .userId(dto.getUserId())
                .noteId(dto.getNoteId())
                .createTime(dto.getCreateTime())
                .status(dto.getType())
                .build();

        noteCollectionDOMapper.insertOrUpdate(noteCollectionDO);
        publishCountRefresh(bodyJsonStr);
    }

    private void handleUnCollectNoteTagMessage(String bodyJsonStr) {
        CollectUnCollectNoteMqDTO dto = JsonUtils.parseObject(bodyJsonStr, CollectUnCollectNoteMqDTO.class);
        if (Objects.isNull(dto)) return;

        NoteCollectionDO noteCollectionDO = NoteCollectionDO.builder()
                .userId(dto.getUserId())
                .noteId(dto.getNoteId())
                .createTime(dto.getCreateTime())
                .status(dto.getType())
                .build();

        noteCollectionDOMapper.update2UnCollectByUserIdAndNoteId(noteCollectionDO);
        publishCountRefresh(bodyJsonStr);
    }

    private void publishCountRefresh(String bodyJsonStr) {
        org.springframework.messaging.Message<String> message = MessageBuilder.withPayload(bodyJsonStr).build();
        rocketMQTemplate.syncSend(MQConstants.TOPIC_COUNT_NOTE_COLLECT, message);
    }
}
