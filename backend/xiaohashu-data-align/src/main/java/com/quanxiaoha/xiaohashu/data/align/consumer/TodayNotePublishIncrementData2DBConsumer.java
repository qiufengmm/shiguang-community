package com.quanxiaoha.xiaohashu.data.align.consumer;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.data.align.constant.MQConstants;
import com.quanxiaoha.xiaohashu.data.align.constant.TableConstants;
import com.quanxiaoha.xiaohashu.data.align.domain.mapper.InsertMapper;
import com.quanxiaoha.xiaohashu.data.align.model.dto.NoteOperateMqDTO;
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
 * 日增量数据落库：笔记发布、删除
 */
@Component
@RocketMQMessageListener(consumerGroup = "xiaohashu_group_data_align_" + MQConstants.TOPIC_NOTE_OPERATE, // Group 组
        topic = MQConstants.TOPIC_NOTE_OPERATE // 主题 Topic
)
@Slf4j
public class TodayNotePublishIncrementData2DBConsumer implements RocketMQListener<String> {

    @Resource
    private InsertMapper insertRecordMapper;

    /**
     * 表总分片数
     */
    @Value("${table.shards}")
    private int tableShards;

    @Override
    public void onMessage(String body) {
        log.info("## TodayNotePublishIncrementData2DBConsumer 消费到了 MQ: {}", body);

        // 消息体 JSON 字符串转 DTO
        NoteOperateMqDTO noteOperateMqDTO = JsonUtils.parseObject(body, NoteOperateMqDTO.class);

        if (Objects.isNull(noteOperateMqDTO)) {
            throw new IllegalArgumentException("Invalid note publish marker message");
        }

        // 笔记的发布者 ID
        Long noteCreatorId = noteOperateMqDTO.getCreatorId();

        requirePositiveId(noteCreatorId);
        requirePositiveShards();

        // 每个实体只需保留一条日标记，由数据库唯一键完成去重。
        // 不用 Bloom 阳性绕过 SQL，避免假阳性漏掉实体。
        String date = LocalDate.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd")); // 转字符串
        insertRecordMapper.insert2DataAlignUserNotePublishCountTempTable(
                TableConstants.buildTableNameSuffix(date, Math.floorMod(noteCreatorId, tableShards)), noteCreatorId);
    }

    private void requirePositiveId(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("Invalid creatorId for note publish marker message");
        }
    }

    private void requirePositiveShards() {
        if (tableShards <= 0) {
            throw new IllegalStateException("table.shards must be positive");
        }
    }
}
