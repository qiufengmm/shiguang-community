package com.quanxiaoha.xiaohashu.comment.biz.consumer;

import cn.hutool.core.collection.CollUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.RateLimiter;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.comment.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.comment.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.comment.biz.service.HotCommentRedisScriptExecutor;
import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentDO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.enums.CommentLevelEnum;
import com.quanxiaoha.xiaohashu.comment.biz.model.bo.CommentBO;
import com.quanxiaoha.xiaohashu.comment.biz.model.dto.CountPublishCommentMqDTO;
import com.quanxiaoha.xiaohashu.comment.biz.model.dto.PublishCommentMqDTO;
import com.quanxiaoha.xiaohashu.comment.biz.rpc.KeyValueRpcService;
import com.quanxiaoha.xiaohashu.comment.biz.service.CommentInboxService;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.protocol.heartbeat.MessageModel;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Collections;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;

/** Batch consumer for published comments. */
@Component
@Slf4j
public class Comment2DBConsumer {

    @Value("${rocketmq.name-server}")
    private String namesrvAddr;

    private DefaultMQPushConsumer consumer;

    private final RateLimiter rateLimiter = RateLimiter.create(1000);

    @Resource
    private CommentInboxService commentInboxService;

    @Resource
    private CommentDOMapper commentDOMapper;

    @Resource
    private KeyValueRpcService keyValueRpcService;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Bean
    public DefaultMQPushConsumer mqPushConsumer() throws MQClientException {
        String group = "xiaohashu_group_" + MQConstants.TOPIC_PUBLISH_COMMENT;
        consumer = new DefaultMQPushConsumer(group);
        consumer.setNamesrvAddr(namesrvAddr);
        consumer.subscribe(MQConstants.TOPIC_PUBLISH_COMMENT, "*");
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setMessageModel(MessageModel.CLUSTERING);
        consumer.setConsumeMessageBatchMaxSize(30);

        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            try {
                for (MessageExt msg : msgs) {
                    String msgJson = new String(msg.getBody(), StandardCharsets.UTF_8);
                    PublishCommentMqDTO dto = JsonUtils.parseObject(msgJson, PublishCommentMqDTO.class);
                    if (dto == null || dto.getCommentId() == null) {
                        throw new IllegalArgumentException("comment publish message is missing commentId");
                    }
                    commentInboxService.receive("comment-publish:" + dto.getCommentId(), "PUBLISH", msgJson);
                    log.info("Comment publish persisted to inbox, commentId={}, msgId={}",
                            dto.getCommentId(), msg.getMsgId());
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            } catch (Exception e) {
                log.error("Comment publish inbox persistence failed", e);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        });

        consumer.start();
        return consumer;
    }

    /**
     * 同步一级评论到 Redis 热点评论 ZSET 中
     */
    private void syncOneLevelComment2RedisZSet(List<CommentBO> commentBOS) {
        Set<Long> noteIds = commentBOS.stream()
                .filter(commentBO -> Objects.equals(commentBO.getLevel(), CommentLevelEnum.ONE.getCode()))
                .map(CommentBO::getNoteId)
                .collect(Collectors.toSet());

        noteIds.forEach(noteId -> {
            String key = RedisKeyConstants.buildCommentListKey(noteId);

            DefaultRedisScript<Long> script = new DefaultRedisScript<>();
            script.setScriptSource(new ResourceScriptSource(new ClassPathResource("/lua/update_hot_comments.lua")));
            script.setResultType(Long.class);

            List<String> keys = List.of(key,
                    RedisKeyConstants.buildCommentListGenerationKey(noteId),
                    RedisKeyConstants.buildCommentListReadyKey(noteId));
            HotCommentRedisScriptExecutor.execute(redisTemplate, script, keys, "INVALIDATE");
        });
    }

    private CommentDO toCommentDO(CommentBO commentBO) {
        return CommentDO.builder()
                .id(commentBO.getId())
                .noteId(commentBO.getNoteId())
                .userId(commentBO.getUserId())
                .contentUuid(commentBO.getContentUuid())
                .isContentEmpty(commentBO.getIsContentEmpty())
                .imageUrl(commentBO.getImageUrl())
                .level(commentBO.getLevel())
                .replyTotal(commentBO.getReplyTotal())
                .likeTotal(commentBO.getLikeTotal())
                .parentId(commentBO.getParentId())
                .replyCommentId(commentBO.getReplyCommentId())
                .replyUserId(commentBO.getReplyUserId())
                .isTop(commentBO.getIsTop())
                .createTime(commentBO.getCreateTime())
                .updateTime(commentBO.getUpdateTime())
                .build();
    }

    @PreDestroy
    public void destroy() {
        if (Objects.nonNull(consumer)) {
            try {
                consumer.shutdown();
            } catch (Exception e) {
                log.error("==> 关闭评论消费者失败", e);
            }
        }
    }
}
