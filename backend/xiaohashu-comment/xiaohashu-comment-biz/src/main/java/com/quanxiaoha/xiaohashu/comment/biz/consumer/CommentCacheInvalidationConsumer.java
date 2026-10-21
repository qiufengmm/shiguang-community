package com.quanxiaoha.xiaohashu.comment.biz.consumer;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.comment.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.comment.biz.model.dto.CommentCacheInvalidationMqDTO;
import com.quanxiaoha.xiaohashu.comment.biz.service.CommentInboxService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RocketMQMessageListener(consumerGroup = "xiaohashu_group_" + MQConstants.TOPIC_COMMENT_CACHE_INVALIDATE,
        topic = MQConstants.TOPIC_COMMENT_CACHE_INVALIDATE)
public class CommentCacheInvalidationConsumer implements RocketMQListener<String>, RocketMQPushConsumerLifecycleListener {
    @Resource
    private CommentInboxService commentInboxService;

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
    }

    @Override
    public void onMessage(String body) {
        CommentCacheInvalidationMqDTO dto = JsonUtils.parseObject(body, CommentCacheInvalidationMqDTO.class);
        if (dto == null || dto.getCommentId() == null) {
            throw new IllegalArgumentException("comment cache invalidation message is missing commentId");
        }
        String eventKey = dto.getEventId() == null || dto.getEventId().isBlank()
                ? "comment-cache-invalidate:" + dto.getCommentId() : dto.getEventId();
        commentInboxService.receive(eventKey, "CACHE_INVALIDATE", body);
        log.info("Comment cache invalidation persisted to inbox, commentId={}", dto.getCommentId());
    }
}
