package com.quanxiaoha.xiaohashu.data.align.consumer;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.data.align.constant.MQConstants;
import com.quanxiaoha.xiaohashu.data.align.constant.TableConstants;
import com.quanxiaoha.xiaohashu.data.align.domain.mapper.InsertMapper;
import com.quanxiaoha.xiaohashu.data.align.model.dto.LikeUnlikeNoteMqDTO;
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
 * 日增量数据落库：笔记点赞、取消点赞
 */
@Component
@RocketMQMessageListener(consumerGroup = "xiaohashu_group_data_align_" + MQConstants.TOPIC_COUNT_NOTE_LIKE, // Group 组
        topic = MQConstants.TOPIC_COUNT_NOTE_LIKE // 主题 Topic
)
@Slf4j
public class TodayNoteLikeIncrementData2DBConsumer implements RocketMQListener<String> {

    @Resource
    private InsertMapper insertRecordMapper;

    /**
     * 表总分片数
     */
    @Value("${table.shards}")
    private int tableShards;

    @Override
    public void onMessage(String body) {
        log.info("## TodayNoteLikeIncrementData2DBConsumer 消费到了 MQ: {}", body);

        // 消息体 JSON 字符串转 DTO
        LikeUnlikeNoteMqDTO unlikeNoteMqDTO = JsonUtils.parseObject(body, LikeUnlikeNoteMqDTO.class);

        if (Objects.isNull(unlikeNoteMqDTO)) {
            throw new IllegalArgumentException("Invalid note like marker message");
        }

        // 被点赞、取消点赞的笔记 ID
        Long noteId = unlikeNoteMqDTO.getNoteId();
        // 笔记的发布者 ID
        Long noteCreatorId = unlikeNoteMqDTO.getNoteCreatorId();

        requirePositiveId(noteId, "noteId");
        requirePositiveId(noteCreatorId, "noteCreatorId");
        requirePositiveShards();

        // SQL 唯一键负责持久化去重。Bloom 阳性不能跳过数据库写入，
        // 否则假阳性会让本次实体变化永远不进入对账集合。
        String date = LocalDate.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd")); // 格式化
        insertRecordMapper.insert2DataAlignNoteLikeCountTempTable(suffix(date, noteId), noteId);
        insertRecordMapper.insert2DataAlignUserLikeCountTempTable(suffix(date, noteCreatorId), noteCreatorId);
    }

    private String suffix(String date, Long entityId) {
        return TableConstants.buildTableNameSuffix(date, Math.floorMod(entityId, tableShards));
    }

    private void requirePositiveId(Long id, String field) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("Invalid " + field + " for note like marker message");
        }
    }

    private void requirePositiveShards() {
        if (tableShards <= 0) {
            throw new IllegalStateException("table.shards must be positive");
        }
    }
}
