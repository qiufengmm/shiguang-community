package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.github.phantomthief.collection.BufferTrigger;
import com.google.common.collect.Maps;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.count.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.count.biz.enums.FollowUnfollowTypeEnum;
import com.quanxiaoha.xiaohashu.count.biz.model.dto.CountFollowUnfollowMqDTO;
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
import java.util.List;
import java.util.Map;

/**
 * 计数：粉丝数
 * <p>
 * 单个大 V 短时间内可能被大量用户关注，逐条落库会把写压力直接打到 MySQL，
 * 因此在消费者侧用 BufferTrigger 做流量聚合（1000 条一批或 1 秒一次），
 * 先写 Redis，再异步削峰落库。
 * <p>
 * 注意：BufferTrigger 缓存在进程内，enqueue 之后消息即被 ACK。
 * 进程被强杀会丢失未攒够一批的数据，依赖 data-align 的日增量标记 + 凌晨分片对账兜底。
 */
@Component
@RocketMQMessageListener(consumerGroup = "xiaohashu_group_" + MQConstants.TOPIC_COUNT_FANS,
        topic = MQConstants.TOPIC_COUNT_FANS)
@Slf4j
public class CountFansConsumer implements RocketMQListener<String> {

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

    /**
     * 停机时强制刷盘。BufferTrigger 自身不会注册 JVM 钩子，
     * 不调用 close() 的话，正常发版重启也会丢掉缓冲区里的残留消息。
     */
    @PreDestroy
    public void flushOnShutdown() {
        bufferTrigger.close();
        log.info("==> 【计数服务：粉丝数】停机前已刷盘缓冲区剩余消息");
    }

    private void consumeMessage(List<String> bodys) {
        try {
            consumeBatch(bodys);
        } catch (Exception e) {
            // 批次已被 BufferTrigger 从缓冲区取出，异常后不会回填，必须留痕以便人工重放
            log.error("==> 【计数服务：粉丝数】聚合批次处理失败，需人工重放，size={}, batch={}",
                    bodys.size(), JsonUtils.toJsonString(bodys), e);
        }
    }

    void consumeBatch(List<String> bodys) {
        Map<Long, Integer> countMap = aggregate(bodys);
        if (countMap.isEmpty()) {
            return;
        }

        // 1. 先写 Redis（key 不存在说明缓存未预热，跳过，交给按需回源）
        countMap.forEach((userId, delta) -> {
            String redisKey = RedisKeyConstants.buildCountUserKey(userId);
            if (Boolean.TRUE.equals(redisTemplate.hasKey(redisKey))) {
                redisTemplate.opsForHash().increment(redisKey, RedisKeyConstants.FIELD_FANS_TOTAL, delta);
            }
        });

        // 2. 再异步削峰落库（一批只发一条 MQ，量级已从 N 降到 N/1000）
        Message<String> message = MessageBuilder.withPayload(JsonUtils.toJsonString(countMap)).build();
        rocketMQTemplate.syncSend(MQConstants.TOPIC_COUNT_FANS_2_DB, message);
        log.info("==> 【计数服务：粉丝数】聚合完成，原始消息 {} 条，聚合后用户 {} 个，已投递落库 MQ",
                bodys.size(), countMap.size());
    }

    /**
     * 按目标用户分组求和。关注 +1、取关 -1；
     * 同一批内净额为 0 的用户直接剔除，避免发出一条无意义的 +0 落库消息。
     */
    Map<Long, Integer> aggregate(List<String> bodys) {
        Map<Long, Integer> countMap = Maps.newHashMap();
        for (String body : bodys) {
            CountFollowUnfollowMqDTO dto = parse(body);
            if (dto == null || dto.getTargetUserId() == null || dto.getType() == null) {
                continue;
            }
            FollowUnfollowTypeEnum type = FollowUnfollowTypeEnum.valueOf(dto.getType());
            if (type == null) {
                continue;
            }
            int delta = type == FollowUnfollowTypeEnum.FOLLOW ? 1 : -1;
            countMap.merge(dto.getTargetUserId(), delta, Integer::sum);
        }
        countMap.entrySet().removeIf(entry -> entry.getValue() == 0);
        return countMap;
    }

    /**
     * 单条消息解析失败只跳过这一条，不能让脏数据把整批 1000 条一起带走。
     */
    private CountFollowUnfollowMqDTO parse(String body) {
        try {
            return JsonUtils.parseObject(body, CountFollowUnfollowMqDTO.class);
        } catch (Exception e) {
            log.warn("==> 【计数服务：粉丝数】跳过无法解析的消息: {}", body);
            return null;
        }
    }
}
