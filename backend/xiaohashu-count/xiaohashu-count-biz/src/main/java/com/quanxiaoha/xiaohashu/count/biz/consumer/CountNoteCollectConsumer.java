package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.github.phantomthief.collection.BufferTrigger;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.count.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.count.biz.enums.CollectUnCollectNoteTypeEnum;
import com.quanxiaoha.xiaohashu.count.biz.model.dto.AggregationCountCollectUnCollectNoteMqDTO;
import com.quanxiaoha.xiaohashu.count.biz.model.dto.CountCollectUnCollectNoteMqDTO;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计数：笔记收藏数
 * <p>
 * 与点赞同理，收藏高峰集中在少数热帖上，用 BufferTrigger 聚合后再落库。
 * 丢失窗口与被强杀时的兜底说明见 {@link CountFansConsumer}。
 */
@Component
@RocketMQMessageListener(consumerGroup = "xiaohashu_group_" + MQConstants.TOPIC_COUNT_NOTE_COLLECT,
        topic = MQConstants.TOPIC_COUNT_NOTE_COLLECT)
@Slf4j
public class CountNoteCollectConsumer implements RocketMQListener<String> {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    private final BufferTrigger<String> bufferTrigger = BufferTrigger.<String>batchBlocking()
            .bufferSize(50000)
            .batchSize(1000)
            .linger(Duration.ofSeconds(1))
            .setConsumerEx(this::consumeMessage)
            .build();

    @Override
    public void onMessage(String body) {
        bufferTrigger.enqueue(body);
    }

    @PreDestroy
    public void flushOnShutdown() {
        bufferTrigger.close();
        log.info("==> 【计数服务：笔记收藏数】停机前已刷盘缓冲区剩余消息");
    }

    private void consumeMessage(List<String> bodys) {
        try {
            consumeBatch(bodys);
        } catch (Exception e) {
            log.error("==> 【计数服务：笔记收藏数】聚合批次处理失败，需人工重放，size={}, batch={}",
                    bodys.size(), JsonUtils.toJsonString(bodys), e);
        }
    }

    void consumeBatch(List<String> bodys) {
        List<AggregationCountCollectUnCollectNoteMqDTO> countList = aggregate(bodys);
        if (countList.isEmpty()) {
            return;
        }

        countList.forEach(item -> {
            String noteRedisKey = RedisKeyConstants.buildCountNoteKey(item.getNoteId());
            if (Boolean.TRUE.equals(redisTemplate.hasKey(noteRedisKey))) {
                redisTemplate.opsForHash().increment(
                        noteRedisKey, RedisKeyConstants.FIELD_COLLECT_TOTAL, item.getCount());
            }
            if (item.getCreatorId() == null) {
                return;
            }
            String userRedisKey = RedisKeyConstants.buildCountUserKey(item.getCreatorId());
            if (Boolean.TRUE.equals(redisTemplate.hasKey(userRedisKey))) {
                redisTemplate.opsForHash().increment(
                        userRedisKey, RedisKeyConstants.FIELD_COLLECT_TOTAL, item.getCount());
            }
        });

        Message<String> message = MessageBuilder.withPayload(JsonUtils.toJsonString(countList)).build();
        rocketMQTemplate.syncSend(MQConstants.TOPIC_COUNT_NOTE_COLLECT_2_DB, message);
        log.info("==> 【计数服务：笔记收藏数】聚合完成，原始消息 {} 条，聚合后笔记 {} 个，已投递落库 MQ",
                bodys.size(), countList.size());
    }

    /**
     * 按笔记维度分组求和，收藏 +1、取消收藏 -1，净额为 0 的笔记直接剔除。
     */
    List<AggregationCountCollectUnCollectNoteMqDTO> aggregate(List<String> bodys) {
        Map<Long, AggregationCountCollectUnCollectNoteMqDTO> merged = new LinkedHashMap<>();
        for (String body : bodys) {
            CountCollectUnCollectNoteMqDTO dto = parse(body);
            if (dto == null || dto.getNoteId() == null || dto.getType() == null) {
                continue;
            }
            CollectUnCollectNoteTypeEnum type = CollectUnCollectNoteTypeEnum.valueOf(dto.getType());
            if (type == null) {
                continue;
            }
            int delta = type == CollectUnCollectNoteTypeEnum.COLLECT ? 1 : -1;
            AggregationCountCollectUnCollectNoteMqDTO acc = merged.get(dto.getNoteId());
            if (acc == null) {
                acc = AggregationCountCollectUnCollectNoteMqDTO.builder()
                        .noteId(dto.getNoteId())
                        .creatorId(dto.getNoteCreatorId())
                        .count(delta)
                        .build();
                merged.put(dto.getNoteId(), acc);
            } else {
                acc.setCount(acc.getCount() + delta);
                if (acc.getCreatorId() == null) {
                    acc.setCreatorId(dto.getNoteCreatorId());
                }
            }
        }
        List<AggregationCountCollectUnCollectNoteMqDTO> countList = new ArrayList<>(merged.size());
        merged.values().forEach(item -> {
            if (item.getCount() != null && item.getCount() != 0) {
                countList.add(item);
            }
        });
        return countList;
    }

    /**
     * 单条消息解析失败只跳过这一条，不能让脏数据把整批 1000 条一起带走。
     */
    private CountCollectUnCollectNoteMqDTO parse(String body) {
        try {
            return JsonUtils.parseObject(body, CountCollectUnCollectNoteMqDTO.class);
        } catch (Exception e) {
            log.warn("==> 【计数服务：笔记收藏数】跳过无法解析的消息: {}", body);
            return null;
        }
    }
}
