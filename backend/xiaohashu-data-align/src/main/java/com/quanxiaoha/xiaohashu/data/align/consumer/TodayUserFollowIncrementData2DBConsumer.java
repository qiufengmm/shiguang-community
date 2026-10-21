package com.quanxiaoha.xiaohashu.data.align.consumer;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.data.align.constant.MQConstants;
import com.quanxiaoha.xiaohashu.data.align.constant.TableConstants;
import com.quanxiaoha.xiaohashu.data.align.domain.mapper.InsertMapper;
import com.quanxiaoha.xiaohashu.data.align.model.dto.FollowUnfollowMqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * 日增量数据落库：用户关注、取消关注
 */
@Component
@RocketMQMessageListener(consumerGroup = "xiaohashu_group_data_align_" + MQConstants.TOPIC_COUNT_FOLLOWING, // Group 组
        topic = MQConstants.TOPIC_COUNT_FOLLOWING // 主题 Topic
)
@Slf4j
public class TodayUserFollowIncrementData2DBConsumer implements RocketMQListener<String> {

    @Resource
    private InsertMapper insertRecordMapper;

    /**
     * 表总分片数
     */
    @Value("${table.shards}")
    private int tableShards;

    @Override
    public void onMessage(String body) {
        log.info("## TodayUserFollowIncrementData2DBConsumer 消费到了 MQ: {}", body);

        // 消息体 JSON 字符串转 DTO
        FollowUnfollowMqDTO followUnfollowMqDTO = JsonUtils.parseObject(body, FollowUnfollowMqDTO.class);

        if (Objects.isNull(followUnfollowMqDTO)) {
            throw new IllegalArgumentException("Invalid follow marker message");
        }

        // 关注/取关操作
        // 源用户 ID
        Long userId = followUnfollowMqDTO.getUserId();
        // 目标用户 ID
        Long targetUserId = followUnfollowMqDTO.getTargetUserId();

        requirePositiveId(userId, "userId");
        requirePositiveId(targetUserId, "targetUserId");
        requirePositiveShards();

        // 按 SQL 唯一键持久化实体集合。不要用 Bloom 阳性跳过数据库写入，
        // Bloom 假阳性会永久漏掉这一实体的日增量标记。
        String date = LocalDate.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd")); // 转字符串
        insertRecordMapper.insert2DataAlignUserFollowingCountTempTable(
                suffix(date, userId), userId);
        insertRecordMapper.insert2DataAlignUserFansCountTempTable(
                suffix(date, targetUserId), targetUserId);
    }

    private String suffix(String date, Long userId) {
        return TableConstants.buildTableNameSuffix(date, Math.floorMod(userId, tableShards));
    }

    private void requirePositiveId(Long id, String field) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("Invalid " + field + " for follow marker message");
        }
    }

    private void requirePositiveShards() {
        if (tableShards <= 0) {
            throw new IllegalStateException("table.shards must be positive");
        }
    }
}
