package com.quanxiaoha.xiaohashu.count.biz.consumer;

import cn.hutool.core.collection.CollUtil;
import com.google.common.util.concurrent.RateLimiter;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CountConsumeLogMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.NoteCountDOMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.UserCountDOMapper;
import com.quanxiaoha.xiaohashu.count.biz.model.dto.AggregationCountLikeUnlikeNoteMqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 计数：笔记点赞数落库
 * <p>
 * 上游已完成按笔记维度的聚合，这里按净增量同时累加「笔记点赞数」和「作者获赞数」。
 * <p>
 * <b>幂等</b>：增量累加必须配合 msgId 去重，否则位点回退重投会把同一批净增量再累加一次。
 */
@Component
@RocketMQMessageListener(consumerGroup = CountNoteLike2DBConsumer.CONSUMER_GROUP,
        topic = MQConstants.TOPIC_COUNT_NOTE_LIKE_2_DB)
@Slf4j
public class CountNoteLike2DBConsumer implements RocketMQListener<MessageExt> {

    static final String CONSUMER_GROUP = "xiaohashu_group_" + MQConstants.TOPIC_COUNT_NOTE_LIKE_2_DB;

    @Resource
    private NoteCountDOMapper noteCountDOMapper;

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
        List<AggregationCountLikeUnlikeNoteMqDTO> countList =
                parse(new String(messageExt.getBody(), StandardCharsets.UTF_8));

        Boolean applied = transactionTemplate.execute(status -> applyOnce(msgId, countList));
        if (Boolean.TRUE.equals(applied)) {
            log.info("## 【计数服务：笔记点赞数落库】已完成 {} 个笔记的增量累加, msgId={}", countList.size(), msgId);
        }
    }

    boolean applyOnce(String msgId, List<AggregationCountLikeUnlikeNoteMqDTO> countList) {
        if (countConsumeLogMapper.insertIgnore(msgId, MQConstants.TOPIC_COUNT_NOTE_LIKE_2_DB, CONSUMER_GROUP) == 0) {
            log.warn("## 【计数服务：笔记点赞数落库】重复消息，整批跳过, msgId={}", msgId);
            return false;
        }
        countList.forEach(item -> {
            if (item.getNoteId() == null || item.getCount() == null || item.getCount() == 0) {
                return;
            }
            noteCountDOMapper.insertOrUpdateLikeTotalByNoteId(item.getCount(), item.getNoteId());
            if (item.getCreatorId() != null) {
                userCountDOMapper.insertOrUpdateLikeTotalByUserId(item.getCount(), item.getCreatorId());
            }
        });
        return true;
    }

    private List<AggregationCountLikeUnlikeNoteMqDTO> parse(String body) {
        List<AggregationCountLikeUnlikeNoteMqDTO> countList;
        try {
            countList = JsonUtils.parseList(body, AggregationCountLikeUnlikeNoteMqDTO.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid note-like count batch", e);
        }
        if (CollUtil.isEmpty(countList)) {
            throw new IllegalArgumentException("Empty note-like count batch");
        }
        return countList;
    }
}
