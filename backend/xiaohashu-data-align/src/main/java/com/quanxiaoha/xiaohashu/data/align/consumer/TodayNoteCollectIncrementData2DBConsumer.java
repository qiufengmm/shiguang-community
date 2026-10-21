package com.quanxiaoha.xiaohashu.data.align.consumer;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.data.align.constant.MQConstants;
import com.quanxiaoha.xiaohashu.data.align.constant.TableConstants;
import com.quanxiaoha.xiaohashu.data.align.domain.mapper.InsertMapper;
import com.quanxiaoha.xiaohashu.data.align.model.dto.CollectUnCollectNoteMqDTO;
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
 * 日增量数据落库：笔记收藏、取消收藏
 */
@Component
@RocketMQMessageListener(consumerGroup = "xiaohashu_group_data_align_" + MQConstants.TOPIC_COUNT_NOTE_COLLECT, // Group 组
        topic = MQConstants.TOPIC_COUNT_NOTE_COLLECT // 主题 Topic
)
@Slf4j
public class TodayNoteCollectIncrementData2DBConsumer implements RocketMQListener<String> {

    @Resource
    private InsertMapper insertRecordMapper;

    /**
     * 表总分片数
     */
    @Value("${table.shards}")
    private int tableShards;

    @Override
    public void onMessage(String body) {
        log.info("## TodayNoteCollectIncrementData2DBConsumer 消费到了 MQ: {}", body);

        // 消息体 JSON 字符串转 DTO
        CollectUnCollectNoteMqDTO collectUnCollectNoteMqDTO = JsonUtils.parseObject(body, CollectUnCollectNoteMqDTO.class);

        if (Objects.isNull(collectUnCollectNoteMqDTO)) {
            throw new IllegalArgumentException("Invalid note collect marker message");
        }

        // 被收藏、取消收藏的笔记 ID
        Long noteId = collectUnCollectNoteMqDTO.getNoteId();
        // 笔记的发布者 ID
        Long noteCreatorId = collectUnCollectNoteMqDTO.getNoteCreatorId();

        requirePositiveId(noteId, "noteId");
        requirePositiveId(noteCreatorId, "noteCreatorId");
        requirePositiveShards();

        // SQL 唯一键负责持久化去重。Bloom 阳性不能跳过数据库写入，
        // 否则假阳性会让本次实体变化永远不进入对账集合。
        String date = LocalDate.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd")); // 格式化
        insertRecordMapper.insert2DataAlignNoteCollectCountTempTable(suffix(date, noteId), noteId);
        insertRecordMapper.insert2DataAlignUserCollectCountTempTable(suffix(date, noteCreatorId), noteCreatorId);
    }

    private String suffix(String date, Long entityId) {
        return TableConstants.buildTableNameSuffix(date, Math.floorMod(entityId, tableShards));
    }

    private void requirePositiveId(Long id, String field) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("Invalid " + field + " for note collect marker message");
        }
    }

    private void requirePositiveShards() {
        if (tableShards <= 0) {
            throw new IllegalStateException("table.shards must be positive");
        }
    }
}
