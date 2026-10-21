package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.google.common.collect.Lists;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.count.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.NoteCountDOMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CommentCountEventMapper;
import com.quanxiaoha.xiaohashu.count.biz.model.dto.CountPublishCommentMqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RocketMQMessageListener(consumerGroup = "xiaohashu_group_" + MQConstants.TOPIC_COUNT_NOTE_COMMENT,
        topic = MQConstants.TOPIC_COUNT_NOTE_COMMENT)
@Slf4j
public class CountNoteCommentConsumer implements RocketMQListener<String>, RocketMQPushConsumerLifecycleListener {

    @Resource
    private NoteCountDOMapper noteCountDOMapper;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

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
        List<CountPublishCommentMqDTO> events = parseEvents(List.of(body));
        Map<Long, Integer> countMap = transactionTemplate.execute(status -> {
            Map<Long, Integer> applied = new java.util.HashMap<>();
            for (CountPublishCommentMqDTO event : events) {
                validate(event);
                int delta = commentCountEventMapper.selectTombstoneForUpdate(event.getCommentId()) == null ? 1 : 0;
                int inserted = commentCountEventMapper.insertIfAbsent(eventId(event), "NOTE_COMMENT_TOTAL",
                        event.getCommentId(), event.getNoteId(), event.getParentId(), delta);
                if (inserted == 1 && delta == 1) {
                    applied.merge(event.getNoteId(), 1, Integer::sum);
                }
            }
            applied.forEach((noteId, delta) -> noteCountDOMapper.insertOrUpdateCommentTotalByNoteId(delta, noteId));
            return applied;
        });
        if (countMap == null) {
            throw new IllegalStateException("comment count transaction returned no result");
        }
        // Invalidate after commit. If Redis is unavailable, RocketMQ redelivery repeats the idempotent SQL
        // transaction and retries invalidation without applying the count twice.
        events.stream().map(CountPublishCommentMqDTO::getNoteId).filter(Objects::nonNull).distinct()
                .forEach(noteId -> redisTemplate.delete(RedisKeyConstants.buildCountNoteKey(noteId)));
        log.info("Applied comment count message, events={}, notes={}", events.size(), countMap.size());
    }

    static Map<Long, Integer> aggregateCommentCounts(List<String> bodies) {
        return parseEvents(bodies).stream().collect(Collectors.groupingBy(CountPublishCommentMqDTO::getNoteId,
                Collectors.summingInt(message -> 1)));
    }

    static List<CountPublishCommentMqDTO> parseEvents(List<String> bodies) {
        List<CountPublishCommentMqDTO> events = Lists.newArrayList();
        for (String body : bodies) {
            List<CountPublishCommentMqDTO> parsed;
            try {
                parsed = JsonUtils.parseList(body, CountPublishCommentMqDTO.class);
            } catch (Exception e) {
                throw new IllegalArgumentException("could not parse comment count event batch", e);
            }
            if (parsed == null || parsed.isEmpty()) {
                throw new IllegalArgumentException("empty comment count event batch");
            }
            events.addAll(parsed);
        }
        return events;
    }

    private static void validate(CountPublishCommentMqDTO event) {
        if (event == null || event.getCommentId() == null || event.getNoteId() == null) {
            throw new IllegalArgumentException("comment count event is missing commentId or noteId");
        }
    }

    private static String eventId(CountPublishCommentMqDTO event) {
        return event.getEventId() == null || event.getEventId().isBlank()
                ? "comment-count-publish:" + event.getCommentId() : event.getEventId();
    }
}
