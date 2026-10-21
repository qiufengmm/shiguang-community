package com.quanxiaoha.xiaohashu.count.biz.consumer;

import cn.hutool.core.collection.CollUtil;
import com.google.common.collect.Lists;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.count.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CommentDOMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CommentCountEventMapper;
import com.quanxiaoha.xiaohashu.count.biz.enums.CommentLevelEnum;
import com.quanxiaoha.xiaohashu.count.biz.model.dto.CountPublishCommentMqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.apache.rocketmq.client.producer.SendStatus;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
@RocketMQMessageListener(consumerGroup = "xiaohashu_group_child_comment_total" + MQConstants.TOPIC_COUNT_NOTE_COMMENT,
        topic = MQConstants.TOPIC_COUNT_NOTE_COMMENT)
@Slf4j
public class CountNoteChildCommentConsumer implements RocketMQListener<String>, RocketMQPushConsumerLifecycleListener {

    @Resource
    private CommentDOMapper commentDOMapper;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Resource
    private CommentCountEventMapper commentCountEventMapper;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
    }

    @Override
    public void onMessage(String body) {
        List<CountPublishCommentMqDTO> events = CountNoteCommentConsumer.parseEvents(List.of(body));
        Map<Long, Integer> childCommentCounts = transactionTemplate.execute(status -> {
            Map<Long, Integer> applied = new java.util.HashMap<>();
            for (CountPublishCommentMqDTO event : events) {
                if (event.getCommentId() == null || event.getNoteId() == null) {
                    throw new IllegalArgumentException("comment count event is missing commentId or noteId");
                }
                if (!Objects.equals(CommentLevelEnum.TWO.getCode(), event.getLevel())) {
                    continue;
                }
                if (event.getParentId() == null) {
                    throw new IllegalArgumentException("child comment count event is missing parentId");
                }
                int delta = commentCountEventMapper.selectTombstoneForUpdate(event.getCommentId()) == null ? 1 : 0;
                String eventId = event.getEventId() == null || event.getEventId().isBlank()
                        ? "comment-count-publish:" + event.getCommentId() : event.getEventId();
                int inserted = commentCountEventMapper.insertIfAbsent(eventId, "CHILD_COMMENT_TOTAL",
                        event.getCommentId(), event.getNoteId(), event.getParentId(), delta);
                if (inserted == 1 && delta == 1) {
                    applied.merge(event.getParentId(), 1, Integer::sum);
                }
            }
            applied.forEach(commentDOMapper::updateChildCommentTotal);
            return applied;
        });
        if (childCommentCounts == null) {
            throw new IllegalStateException("child comment count transaction returned no result");
        }
        events.stream().filter(event -> Objects.equals(CommentLevelEnum.TWO.getCode(), event.getLevel()))
                .map(CountPublishCommentMqDTO::getParentId).filter(Objects::nonNull).distinct()
                .forEach(parentId -> redisTemplate.delete(RedisKeyConstants.buildCountCommentKey(parentId)));

        List<Long> parentIds = events.stream().filter(event -> Objects.equals(CommentLevelEnum.TWO.getCode(), event.getLevel()))
                .map(CountPublishCommentMqDTO::getParentId).filter(Objects::nonNull).distinct().toList();
        if (!parentIds.isEmpty()) {
            var message = MessageBuilder.withPayload(JsonUtils.toJsonString(parentIds)).build();
            SendResult result = rocketMQTemplate.syncSend(MQConstants.TOPIC_COMMENT_HEAT_UPDATE, message);
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("RocketMQ did not confirm comment heat update");
            }
        }
        log.info("Applied child comment count message, events={}, parents={}", events.size(), childCommentCounts.size());
    }

    static Map<Long, Integer> aggregateChildCommentCounts(List<String> bodies) {
        List<CountPublishCommentMqDTO> messages = Lists.newArrayList();
        bodies.forEach(body -> {
            try {
                List<CountPublishCommentMqDTO> parsed = JsonUtils.parseList(body, CountPublishCommentMqDTO.class);
                if (CollUtil.isNotEmpty(parsed)) {
                    messages.addAll(parsed);
                }
            } catch (Exception e) {
                log.error("二级评论计数消息解析失败", e);
            }
        });

        return messages.stream()
                .filter(message -> Objects.equals(CommentLevelEnum.TWO.getCode(), message.getLevel()))
                .collect(Collectors.groupingBy(CountPublishCommentMqDTO::getParentId,
                        Collectors.summingInt(message -> 1)));
    }
}
