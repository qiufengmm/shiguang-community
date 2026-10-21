package com.quanxiaoha.xiaohashu.note.biz.consumer;

import cn.hutool.core.collection.CollUtil;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.RateLimiter;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.note.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteLikeDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteLikeDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.LikeUnlikeNoteMqDTO;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.protocol.heartbeat.MessageModel;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 笔记点赞、取消点赞 MQ 消费者
 */
@Component
@Slf4j
public class LikeUnlikeNoteConsumer {

    @Value("${rocketmq.name-server}")
    private String namesrvAddr;

    @Resource
    private NoteLikeDOMapper noteLikeDOMapper;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    private DefaultMQPushConsumer consumer;

    // 每秒创建 5000 个令牌
    private final RateLimiter rateLimiter = RateLimiter.create(5000);

    @Bean(name = "LikeUnlikeNoteConsumer")
    public DefaultMQPushConsumer mqPushConsumer() throws MQClientException {
        String group = "xiaohashu_group1_" + MQConstants.TOPIC_LIKE_OR_UNLIKE;
        consumer = new DefaultMQPushConsumer(group);
        consumer.setNamesrvAddr(namesrvAddr);
        consumer.subscribe(MQConstants.TOPIC_LIKE_OR_UNLIKE, "*");
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        consumer.setMessageModel(MessageModel.CLUSTERING);
        consumer.setMaxReconsumeTimes(3);
        consumer.setConsumeMessageBatchMaxSize(30);
        consumer.setPullInterval(1000);

        consumer.registerMessageListener((MessageListenerOrderly) (msgs, context) -> {
            log.info("==> 【笔记点赞、取消点赞】本批次消息大小: {}", msgs.size());
            try {
                rateLimiter.acquire();

                List<LikeUnlikeNoteMqDTO> likeUnlikeNoteMqDTOS = Lists.newArrayList();
                msgs.forEach(msg -> {
                    String msgJson = new String(msg.getBody());
                    log.info("==> Consumer - Received message: {}", msgJson);
                    likeUnlikeNoteMqDTOS.add(JsonUtils.parseObject(msgJson, LikeUnlikeNoteMqDTO.class));
                });

                persistAndPublishCountEvents(likeUnlikeNoteMqDTOS);

                return ConsumeOrderlyStatus.SUCCESS;
            } catch (Exception e) {
                log.error("==> 笔记点赞、取消点赞批量消费失败", e);
                return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
            }
        });

        consumer.start();
        return consumer;
    }

    void persistAndPublishCountEvents(List<LikeUnlikeNoteMqDTO> operations) {
        List<LikeUnlikeNoteMqDTO> finalOperations = mergeOperations(operations);
        if (CollUtil.isEmpty(finalOperations)) return;

        List<NoteLikeDO> noteLikeDOS = finalOperations.stream()
                .map(operation -> NoteLikeDO.builder()
                        .userId(operation.getUserId())
                        .noteId(operation.getNoteId())
                        .createTime(operation.getCreateTime())
                        .status(operation.getType())
                        .build())
                .toList();
        // Persist state first. Replayed state-setting upserts are idempotent.
        noteLikeDOMapper.batchInsertOrUpdate(noteLikeDOS);

        // Synchronous send makes the source MQ message retry if count refresh cannot be enqueued.
        // Duplicate refresh events are safe because consumers recalculate from committed source rows.
        for (LikeUnlikeNoteMqDTO operation : finalOperations) {
            Message<String> message = MessageBuilder.withPayload(JsonUtils.toJsonString(operation)).build();
            rocketMQTemplate.syncSend(MQConstants.TOPIC_COUNT_NOTE_LIKE, message);
        }
    }

    static List<LikeUnlikeNoteMqDTO> mergeOperations(List<LikeUnlikeNoteMqDTO> operations) {
        Map<UserNoteKey, LikeUnlikeNoteMqDTO> latestOperationByKey = new LinkedHashMap<>();
        for (LikeUnlikeNoteMqDTO operation : operations) {
            latestOperationByKey.put(new UserNoteKey(operation.getUserId(), operation.getNoteId()), operation);
        }
        return List.copyOf(latestOperationByKey.values());
    }

    private record UserNoteKey(Long userId, Long noteId) { }

    @PreDestroy
    public void destroy() {
        if (Objects.nonNull(consumer)) {
            try {
                consumer.shutdown();
            } catch (Exception e) {
                log.error("==> 关闭笔记点赞、取消点赞消费者失败", e);
            }
        }
    }
}
