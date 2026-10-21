package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.google.common.util.concurrent.RateLimiter;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CountConsumeLogMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.UserCountDOMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 计数：粉丝数落库
 * <p>
 * 上游 {@link CountFansConsumer} 已把一批消息聚合成 {@code Map<userId, 净增量>}，
 * 这里只需要按增量累加，一次批次只做一批 UPDATE，不再回源 COUNT。
 * <p>
 * 令牌桶用于削峰：即使上游整批涌入，也按每秒 5000 个令牌的速率写入 MySQL。
 * <p>
 * <b>幂等</b>：{@code x = x + delta} 天然不可重放。RocketMQ 在进程崩溃时会从上次提交的位点重投，
 * 已经累加过的批次会被再累加一次。所以这里用 {@code t_count_consume_log} 按 msgId 去重，
 * 且“登记 + 累加”跑在同一个本地事务里，崩溃只可能整体回滚或整体生效。
 */
@Component
@RocketMQMessageListener(consumerGroup = CountFans2DBConsumer.CONSUMER_GROUP,
        topic = MQConstants.TOPIC_COUNT_FANS_2_DB)
@Slf4j
public class CountFans2DBConsumer implements RocketMQListener<MessageExt> {

    static final String CONSUMER_GROUP = "xiaohashu_group_" + MQConstants.TOPIC_COUNT_FANS_2_DB;

    @Resource
    private UserCountDOMapper userCountDOMapper;

    @Resource
    private CountConsumeLogMapper countConsumeLogMapper;

    @Resource
    private TransactionTemplate transactionTemplate;

    private final RateLimiter rateLimiter = RateLimiter.create(5000);

    @Override
    public void onMessage(MessageExt messageExt) {
        rateLimiter.acquire();

        String msgId = messageExt.getMsgId();
        Map<Long, Integer> countMap = parse(new String(messageExt.getBody(), StandardCharsets.UTF_8));

        Boolean applied = transactionTemplate.execute(status -> applyOnce(msgId, countMap));
        if (Boolean.TRUE.equals(applied)) {
            log.info("## 【计数服务：粉丝数落库】已完成 {} 个用户的增量累加, msgId={}", countMap.size(), msgId);
        }
    }

    /**
     * 幂等地应用一批增量。返回 {@code false} 表示这条消息之前已经应用过，整批跳过。
     * <p>
     * 必须由事务包裹调用，否则「登记」与「累加」之间崩溃会留下不一致。
     */
    boolean applyOnce(String msgId, Map<Long, Integer> countMap) {
        if (countConsumeLogMapper.insertIgnore(msgId, MQConstants.TOPIC_COUNT_FANS_2_DB, CONSUMER_GROUP) == 0) {
            log.warn("## 【计数服务：粉丝数落库】重复消息，整批跳过, msgId={}", msgId);
            return false;
        }
        countMap.forEach((userId, delta) -> {
            if (delta == null || delta == 0) {
                return;
            }
            userCountDOMapper.insertOrUpdateFansTotalByUserId(delta, userId);
        });
        return true;
    }

    private Map<Long, Integer> parse(String body) {
        Map<Long, Integer> countMap;
        try {
            countMap = JsonUtils.parseMap(body, Long.class, Integer.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid fans count batch", e);
        }
        if (countMap == null || countMap.isEmpty()) {
            throw new IllegalArgumentException("Empty fans count batch");
        }
        for (Long userId : countMap.keySet()) {
            if (userId == null || userId <= 0) {
                throw new IllegalArgumentException("Invalid userId in fans count batch");
            }
        }
        return countMap;
    }
}
