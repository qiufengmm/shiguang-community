package com.quanxiaoha.xiaohashu.comment.biz.consumer;

import cn.hutool.core.collection.CollUtil;
import com.google.common.util.concurrent.RateLimiter;
import com.google.common.collect.Lists;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.comment.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentLikeDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.enums.LikeUnlikeCommentTypeEnum;
import com.quanxiaoha.xiaohashu.comment.biz.model.dto.LikeUnlikeCommentMqDTO;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.protocol.heartbeat.MessageModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@Slf4j
public class LikeUnlikeComment2DBConsumer {

    @Value("${rocketmq.name-server}")
    private String namesrvAddr;

    private DefaultMQPushConsumer consumer;

    private final RateLimiter rateLimiter = RateLimiter.create(5000);

    @Resource
    private CommentLikeDOMapper commentLikeDOMapper;

    @Bean(name = "LikeUnlikeComment2DBConsumer")
    public DefaultMQPushConsumer mqPushConsumer() throws MQClientException {
        String group = "xiaohashu_group_" + MQConstants.TOPIC_COMMENT_LIKE_OR_UNLIKE;
        consumer = new DefaultMQPushConsumer(group);
        consumer.setNamesrvAddr(namesrvAddr);
        consumer.subscribe(MQConstants.TOPIC_COMMENT_LIKE_OR_UNLIKE, "*");
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        consumer.setMessageModel(MessageModel.CLUSTERING);
        consumer.setMaxReconsumeTimes(3);
        consumer.setConsumeMessageBatchMaxSize(30);

        consumer.registerMessageListener((MessageListenerOrderly) (msgs, context) -> {
            log.info("==> 【评论点赞、取消点赞】本批次消息大小: {}", msgs.size());
            try {
                rateLimiter.acquire();
                processMessages(msgs);
                return ConsumeOrderlyStatus.SUCCESS;
            } catch (Exception e) {
                log.error("==> 评论点赞、取消点赞批次消费失败", e);
                return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
            }
        });

        consumer.start();
        return consumer;
    }

    void processMessages(List<MessageExt> msgs) {
        List<LikeUnlikeCommentMqDTO> likeUnlikeCommentMqDTOS = Lists.newArrayList();
        msgs.forEach(msg -> {
            String tag = msg.getTags();
            String msgJson = new String(msg.getBody());
            log.info("==> 【评论点赞、取消点赞】Consumer - Tag: {}, Received message: {}", tag, msgJson);
            likeUnlikeCommentMqDTOS.add(JsonUtils.parseObject(msgJson, LikeUnlikeCommentMqDTO.class));
        });

        Map<Long, List<LikeUnlikeCommentMqDTO>> commentIdAndListMap = likeUnlikeCommentMqDTOS.stream()
                .collect(Collectors.groupingBy(LikeUnlikeCommentMqDTO::getCommentId));
        List<LikeUnlikeCommentMqDTO> finalLikeUnlikeCommentMqDTOS = Lists.newArrayList();

        commentIdAndListMap.forEach((commentId, ops) -> {
            Map<Long, LikeUnlikeCommentMqDTO> userLastOp = ops.stream()
                    .collect(Collectors.toMap(
                            LikeUnlikeCommentMqDTO::getUserId,
                            Function.identity(),
                            (oldValue, newValue) -> oldValue.getCreateTime().isAfter(newValue.getCreateTime())
                                    ? oldValue : newValue));
            finalLikeUnlikeCommentMqDTOS.addAll(userLastOp.values());
        });

        executeBatchSQL(finalLikeUnlikeCommentMqDTOS);
    }

    private void executeBatchSQL(Collection<LikeUnlikeCommentMqDTO> values) {
        List<LikeUnlikeCommentMqDTO> likes = values.stream()
                .filter(op -> Objects.equals(op.getType(), LikeUnlikeCommentTypeEnum.LIKE.getCode()))
                .toList();
        List<LikeUnlikeCommentMqDTO> unlikes = values.stream()
                .filter(op -> Objects.equals(op.getType(), LikeUnlikeCommentTypeEnum.UNLIKE.getCode()))
                .toList();

        if (CollUtil.isNotEmpty(unlikes)) {
            commentLikeDOMapper.batchDelete(unlikes);
        }
        if (CollUtil.isNotEmpty(likes)) {
            commentLikeDOMapper.batchInsert(likes);
        }
    }

    @PreDestroy
    public void destroy() {
        if (Objects.nonNull(consumer)) {
            try {
                consumer.shutdown();
            } catch (Exception e) {
                log.error("==> 关闭评论点赞消费者失败", e);
            }
        }
    }
}
