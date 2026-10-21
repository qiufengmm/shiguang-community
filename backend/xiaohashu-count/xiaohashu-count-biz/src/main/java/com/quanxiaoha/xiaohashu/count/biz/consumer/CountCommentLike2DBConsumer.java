package com.quanxiaoha.xiaohashu.count.biz.consumer;

import cn.hutool.core.collection.CollUtil;
import com.google.common.util.concurrent.RateLimiter;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CommentDOMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CountConsumeLogMapper;
import com.quanxiaoha.xiaohashu.count.biz.model.dto.AggregationCountLikeUnlikeCommentMqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 计数：评论点赞数落库
 * <p>
 * <b>幂等</b>：{@code like_total = like_total + delta} 不可重放，落库前用 msgId 去重，
 * 去重登记与增量更新同事务。
 */
@Component
@RocketMQMessageListener(consumerGroup = CountCommentLike2DBConsumer.CONSUMER_GROUP,
        topic = MQConstants.TOPIC_COUNT_COMMENT_LIKE_2_DB)
@Slf4j
public class CountCommentLike2DBConsumer implements RocketMQListener<MessageExt>, RocketMQPushConsumerLifecycleListener {

    static final String CONSUMER_GROUP = "xiaohashu_group_" + MQConstants.TOPIC_COUNT_COMMENT_LIKE_2_DB;

    @Resource
    private CommentDOMapper commentDOMapper;

    @Resource
    private CountConsumeLogMapper countConsumeLogMapper;

    @Resource
    private TransactionTemplate transactionTemplate;

    private final RateLimiter rateLimiter = RateLimiter.create(5000);

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
    }

    @Override
    public void onMessage(MessageExt messageExt) {
        rateLimiter.acquire();

        String msgId = messageExt.getMsgId();
        List<AggregationCountLikeUnlikeCommentMqDTO> counts =
                parse(new String(messageExt.getBody(), StandardCharsets.UTF_8));
        if (CollUtil.isEmpty(counts)) {
            return;
        }

        Boolean applied = transactionTemplate.execute(status -> applyOnce(msgId, counts));
        if (Boolean.TRUE.equals(applied)) {
            log.info("## 【计数服务：评论点赞数落库】已完成 {} 条评论的增量累加, msgId={}", counts.size(), msgId);
        }
    }

    boolean applyOnce(String msgId, List<AggregationCountLikeUnlikeCommentMqDTO> counts) {
        if (countConsumeLogMapper.insertIgnore(msgId, MQConstants.TOPIC_COUNT_COMMENT_LIKE_2_DB, CONSUMER_GROUP) == 0) {
            log.warn("## 【计数服务：评论点赞数落库】重复消息，整批跳过, msgId={}", msgId);
            return false;
        }
        counts.forEach(item -> {
            if (item.getCommentId() == null || item.getCount() == null || item.getCount() == 0) {
                return;
            }
            commentDOMapper.updateLikeTotalByCommentId(item.getCount(), item.getCommentId());
        });
        return true;
    }

    private List<AggregationCountLikeUnlikeCommentMqDTO> parse(String body) {
        try {
            return JsonUtils.parseList(body, AggregationCountLikeUnlikeCommentMqDTO.class);
        } catch (Exception e) {
            log.error("## 解析评论点赞计数消息失败, body={}", body, e);
            return null;
        }
    }
}
