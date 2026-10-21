package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.count.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.count.biz.domain.dataobject.CommentDO;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CommentCountEventMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CommentDOMapper;
import com.quanxiaoha.xiaohashu.count.biz.enums.LikeUnlikeCommentTypeEnum;
import com.quanxiaoha.xiaohashu.count.biz.model.dto.CountLikeUnlikeCommentMqDTO;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
@Slf4j
public class CountCommentLikeConsumer {
    private static final String COUNTER_TYPE = "COMMENT_LIKE_TOTAL";
    private static final String LIKE_STATE_COUNTER_TYPE = "COMMENT_LIKE_STATE";

    @Value("${rocketmq.name-server}")
    private String namesrvAddr;

    private DefaultMQPushConsumer consumer;

    @Resource
    private CommentDOMapper commentDOMapper;

    @Resource
    private CommentCountEventMapper commentCountEventMapper;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Bean(name = "CountCommentLikeConsumer")
    public DefaultMQPushConsumer countCommentLikeConsumer() throws MQClientException {
        consumer = new DefaultMQPushConsumer("xiaohashu_group_count_" + MQConstants.TOPIC_COMMENT_LIKE_OR_UNLIKE);
        consumer.setNamesrvAddr(namesrvAddr);
        consumer.subscribe(MQConstants.TOPIC_COMMENT_LIKE_OR_UNLIKE, "*");
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setConsumeMessageBatchMaxSize(10);
        consumer.registerMessageListener((MessageListenerOrderly) (messages, context) -> {
            try {
                for (MessageExt message : messages) {
                    processEvent(message.getMsgId(), new String(message.getBody(), StandardCharsets.UTF_8));
                }
                return ConsumeOrderlyStatus.SUCCESS;
            } catch (Exception e) {
                log.error("Comment-like count persistence failed; requesting RocketMQ redelivery", e);
                return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
            }
        });
        consumer.start();
        return consumer;
    }

    /** Apply one RocketMQ delivery idempotently; safe to call again after Redis or MQ follow-up failures. */
    void processEvent(String messageId, String body) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("comment-like event is missing RocketMQ message id");
        }
        CountLikeUnlikeCommentMqDTO operation;
        try {
            operation = JsonUtils.parseObject(body, CountLikeUnlikeCommentMqDTO.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("could not parse comment-like event", e);
        }
        if (operation == null || operation.getUserId() == null || operation.getCommentId() == null
                || operation.getType() == null) {
            throw new IllegalArgumentException("comment-like event is missing userId, commentId, or type");
        }
        LikeUnlikeCommentTypeEnum type = LikeUnlikeCommentTypeEnum.valueOf(operation.getType());
        if (type == null) {
            throw new IllegalArgumentException("comment-like event has an unsupported type");
        }
        int desiredState = type == LikeUnlikeCommentTypeEnum.LIKE ? 1 : 0;

        Boolean applied = transactionTemplate.execute(status -> {
            // The delete transaction writes the tombstone first. Locking it serializes a late like against deletion.
            if (commentCountEventMapper.selectTombstoneForUpdate(operation.getCommentId()) != null) {
                return false;
            }
            CommentDO comment = commentDOMapper.selectByPrimaryKey(operation.getCommentId());
            if (comment == null) {
                return false;
            }
            int inserted = commentCountEventMapper.insertIfAbsent("comment-like:" + messageId, COUNTER_TYPE,
                    comment.getId(), comment.getNoteId(), comment.getParentId(), desiredState == 1 ? 1 : -1);
            if (inserted == 1) {
                String stateEventId = buildLikeStateEventId(operation.getUserId(), comment.getId());
                commentCountEventMapper.insertIfAbsent(stateEventId, LIKE_STATE_COUNTER_TYPE,
                        comment.getId(), comment.getNoteId(), comment.getParentId(), 1 - desiredState);
                Integer currentState = commentCountEventMapper.selectAppliedDeltaForUpdate(
                        stateEventId, LIKE_STATE_COUNTER_TYPE);
                if (currentState == null) {
                    throw new IllegalStateException("comment-like state row was not created");
                }
                if (currentState != desiredState) {
                    int delta = desiredState - currentState;
                    commentCountEventMapper.updateAppliedDelta(stateEventId, LIKE_STATE_COUNTER_TYPE, desiredState);
                    commentDOMapper.updateLikeTotalByCommentId(delta, comment.getId());
                }
            }
            return true;
        });
        if (applied == null) {
            throw new IllegalStateException("comment-like count transaction returned no result");
        }
        if (!applied) {
            log.info("Ignoring comment-like event for deleted or missing comment, commentId={}", operation.getCommentId());
            return;
        }

        redisTemplate.delete(RedisKeyConstants.buildCountCommentKey(operation.getCommentId()));
        Message<String> heatMessage = MessageBuilder.withPayload(JsonUtils.toJsonString(Set.of(operation.getCommentId()))).build();
        var result = rocketMQTemplate.syncSend(MQConstants.TOPIC_COMMENT_HEAT_UPDATE, heatMessage);
        if (result == null || result.getSendStatus() != org.apache.rocketmq.client.producer.SendStatus.SEND_OK) {
            throw new IllegalStateException("RocketMQ did not confirm comment heat update");
        }
        log.info("Applied comment-like event, commentId={}, messageId={}", operation.getCommentId(), messageId);
    }

    private static String buildLikeStateEventId(Long userId, Long commentId) {
        return "comment-like-state:" + userId + ":" + commentId;
    }

    @PreDestroy
    public void destroy() {
        if (Objects.nonNull(consumer)) {
            try {
                consumer.shutdown();
            } catch (Exception e) {
                log.error("Failed to shut down comment-like count consumer", e);
            }
        }
    }
}
