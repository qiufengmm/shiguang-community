package com.quanxiaoha.xiaohashu.comment.biz.consumer;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.comment.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.comment.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.comment.biz.service.HotCommentRedisScriptExecutor;
import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentDO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.model.bo.CommentHeatBO;
import com.quanxiaoha.xiaohashu.comment.biz.util.HeatCalculator;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
@RocketMQMessageListener(consumerGroup = "xiaohashu_group_" + MQConstants.TOPIC_COMMENT_HEAT_UPDATE,
        topic = MQConstants.TOPIC_COMMENT_HEAT_UPDATE)
@Slf4j
public class CommentHeatUpdateConsumer implements RocketMQListener<String>, RocketMQPushConsumerLifecycleListener {

    @Resource
    private CommentDOMapper commentDOMapper;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
    }

    @Override
    public void onMessage(String body) {
        Set<Long> commentIds = collectCommentIds(List.of(body));
        updateHeat(commentIds);
    }

    private void updateHeat(Set<Long> commentIds) {
        log.info("Recalculating comment heat, commentIds={}", commentIds);
        if (commentIds.isEmpty()) {
            return;
        }

        List<CommentDO> commentDOS = commentDOMapper.selectByCommentIds(commentIds.stream().toList());
        List<Long> ids = Lists.newArrayList();
        List<CommentHeatBO> commentBOS = Lists.newArrayList();
        commentDOS.forEach(commentDO -> {
            Long commentId = commentDO.getId();
            BigDecimal heatNum = HeatCalculator.calculateHeat(commentDO.getLikeTotal(), commentDO.getChildCommentTotal());
            ids.add(commentId);
            commentBOS.add(CommentHeatBO.builder()
                    .id(commentId)
                    .noteId(commentDO.getNoteId())
                    .heat(heatNum.doubleValue())
                    .build());
        });

        if (!ids.isEmpty()) {
            commentDOMapper.batchUpdateHeatByCommentIds(ids, commentBOS);
            invalidateRedisHotComments(commentBOS);
        }
    }

    /**
     * 更新 Redis 中热点评论 ZSET
     */
    private void invalidateRedisHotComments(List<CommentHeatBO> commentHeatBOList) {
        Map<Long, List<CommentHeatBO>> noteIdAndBOListMap = commentHeatBOList.stream()
                .collect(Collectors.groupingBy(CommentHeatBO::getNoteId));

        noteIdAndBOListMap.forEach((noteId, commentHeatBOS) -> {
            String key = RedisKeyConstants.buildCommentListKey(noteId);

            DefaultRedisScript<Long> script = new DefaultRedisScript<>();
            script.setScriptSource(new ResourceScriptSource(new ClassPathResource("/lua/update_hot_comments.lua")));
            script.setResultType(Long.class);

            List<String> keys = List.of(key,
                    RedisKeyConstants.buildCommentListGenerationKey(noteId),
                    RedisKeyConstants.buildCommentListReadyKey(noteId));
            // Invalidate on every delivery. If SQL committed and Redis failed, RocketMQ redelivery still
            // invalidates the cache even though a second SQL update reports no changed rows.
            HotCommentRedisScriptExecutor.execute(redisTemplate, script, keys, "INVALIDATE");
        });
    }

    static Set<Long> collectCommentIds(List<String> bodies) {
        Set<Long> commentIds = Sets.newHashSet();
        for (String body : bodies) {
            Set<Long> parsed;
            try {
                parsed = JsonUtils.parseSet(body, Long.class);
            } catch (Exception e) {
                throw new IllegalArgumentException("could not parse comment heat update event", e);
            }
            if (parsed == null || parsed.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("invalid comment heat update event");
            }
            commentIds.addAll(parsed);
        }
        return commentIds;
    }
}
