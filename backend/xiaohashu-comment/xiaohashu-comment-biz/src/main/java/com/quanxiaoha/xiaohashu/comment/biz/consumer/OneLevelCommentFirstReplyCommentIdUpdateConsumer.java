package com.quanxiaoha.xiaohashu.comment.biz.consumer;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import com.google.common.collect.Lists;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.comment.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.comment.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentDO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.enums.CommentLevelEnum;
import com.quanxiaoha.xiaohashu.comment.biz.model.dto.CountPublishCommentMqDTO;
import com.quanxiaoha.xiaohashu.comment.biz.service.CommentService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
@RocketMQMessageListener(consumerGroup = "xiaohashu_group_first_reply_comment_id" + MQConstants.TOPIC_COUNT_NOTE_COMMENT,
        topic = MQConstants.TOPIC_COUNT_NOTE_COMMENT)
@Slf4j
public class OneLevelCommentFirstReplyCommentIdUpdateConsumer implements RocketMQListener<String>, RocketMQPushConsumerLifecycleListener {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private CommentDOMapper commentDOMapper;

    @Resource
    private CommentService commentService;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
    }

    @Override
    public void onMessage(String body) {
        consumeMessage(List.of(body));
    }

    private void consumeMessage(List<String> bodies) {
        log.info("Processing first-reply cache update, messages={}", bodies.size());

        List<Long> parentIds = extractParentIds(bodies);
        if (CollUtil.isEmpty(parentIds)) {
            return;
        }

        List<String> keys = parentIds.stream()
                .map(RedisKeyConstants::buildHaveFirstReplyCommentKey)
                .toList();
        List<Object> values = redisTemplate.opsForValue().multiGet(keys);

        List<Long> missingCommentIds = Lists.newArrayList();
        for (int i = 0; i < parentIds.size(); i++) {
            if (values == null || i >= values.size() || Objects.isNull(values.get(i))) {
                missingCommentIds.add(parentIds.get(i));
            }
        }

        Set<Long> missingCommentIdSet = new HashSet<>(missingCommentIds);
        // The Redis marker can outlive a deleted last reply. Check the SQL pointer on every reply
        // event so a stale marker cannot block the next first-reply pointer update.
        List<CommentDO> commentDOS = commentDOMapper.selectByCommentIds(parentIds);
        if (CollUtil.isEmpty(commentDOS)) {
            return;
        }
        List<Long> needSyncCommentIds = commentDOS.stream()
                .filter(commentDO -> Objects.nonNull(commentDO.getFirstReplyCommentId())
                        && commentDO.getFirstReplyCommentId() != 0
                        && missingCommentIdSet.contains(commentDO.getId()))
                .map(CommentDO::getId)
                .toList();
        needSyncCommentIds.forEach(this::invalidateParentPreviewCaches);
        sync2Redis(needSyncCommentIds);

        List<CommentDO> needUpdateCommentDOS = commentDOS.stream()
                .filter(commentDO -> Objects.isNull(commentDO.getFirstReplyCommentId())
                        || commentDO.getFirstReplyCommentId() == 0)
                .toList();
        needUpdateCommentDOS.forEach(commentDO -> {
            Long commentId = commentDO.getId();
            CommentDO earliestCommentDO = commentDOMapper.selectEarliestByParentId(commentId);
            if (Objects.nonNull(earliestCommentDO)) {
                Long earliestCommentId = earliestCommentDO.getId();
                int updated = commentDOMapper.updateFirstReplyCommentIdByPrimaryKey(earliestCommentId, commentId);
                if (updated > 0) {
                    invalidateParentPreviewCaches(commentId);
                    sync2Redis(Lists.newArrayList(commentId));
                }
            }
        });
    }

    private void invalidateParentPreviewCaches(Long commentId) {
        redisTemplate.delete(RedisKeyConstants.buildCommentDetailKey(commentId));
        commentService.deleteCommentLocalCache(commentId);
        SendResult result = rocketMQTemplate.syncSend(MQConstants.TOPIC_DELETE_COMMENT_LOCAL_CACHE,
                String.valueOf(commentId));
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("RocketMQ did not confirm comment preview cache invalidation");
        }
    }

    static List<Long> extractParentIds(List<String> bodies) {
        List<CountPublishCommentMqDTO> messages = Lists.newArrayList();
        bodies.forEach(body -> {
            List<CountPublishCommentMqDTO> parsed;
            try {
                parsed = JsonUtils.parseList(body, CountPublishCommentMqDTO.class);
            } catch (Exception e) {
                throw new IllegalArgumentException("could not parse first-reply update event batch", e);
            }
            if (CollUtil.isEmpty(parsed)) {
                throw new IllegalArgumentException("empty first-reply update event batch");
            }
            messages.addAll(parsed);
        });
        return messages.stream()
                .filter(message -> Objects.equals(message.getLevel(), CommentLevelEnum.TWO.getCode()))
                .map(CountPublishCommentMqDTO::getParentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private void sync2Redis(List<Long> commentIds) {
        ValueOperations<String, Object> valueOperations = redisTemplate.opsForValue();
        redisTemplate.executePipelined((RedisCallback<?>) connection -> {
            commentIds.forEach(commentId -> {
                String key = RedisKeyConstants.buildHaveFirstReplyCommentKey(commentId);
                valueOperations.set(key, 1, RandomUtil.randomInt(5 * 60 * 60), TimeUnit.SECONDS);
            });
            return null;
        });
    }
}
