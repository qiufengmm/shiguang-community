package com.quanxiaoha.xiaohashu.comment.biz.consumer;

import com.quanxiaoha.xiaohashu.comment.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentDO;
import com.quanxiaoha.xiaohashu.comment.biz.service.CommentInboxService;
import com.quanxiaoha.framework.common.util.JsonUtils;
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
@RocketMQMessageListener(consumerGroup = "xiaohashu_group_" + MQConstants.TOPIC_DELETE_COMMENT,
        topic = MQConstants.TOPIC_DELETE_COMMENT)
public class DeleteCommentConsumer implements RocketMQListener<String>, RocketMQPushConsumerLifecycleListener {

    @Resource
    private CommentInboxService commentInboxService;

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
    }

    @Override
    public void onMessage(String body) {
        CommentDO commentDO = JsonUtils.parseObject(body, CommentDO.class);
        if (commentDO == null || commentDO.getId() == null) {
            throw new IllegalArgumentException("comment delete message is missing commentId");
        }
        commentInboxService.receive("comment-delete:" + commentDO.getId(), "DELETE", body);
        log.info("Comment delete persisted to inbox, commentId={}", commentDO.getId());
    }
}
