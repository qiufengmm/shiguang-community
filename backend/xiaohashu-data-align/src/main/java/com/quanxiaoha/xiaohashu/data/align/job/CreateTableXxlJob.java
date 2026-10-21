package com.quanxiaoha.xiaohashu.data.align.job;

import com.quanxiaoha.xiaohashu.data.align.constant.TableConstants;
import com.quanxiaoha.xiaohashu.data.align.domain.mapper.CreateTableMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 定时任务：自动创建日增量计数变更表
 */
@Component
@RefreshScope
public class CreateTableXxlJob {

    @Value("${table.shards}")
    private int tableShards;

    @Resource
    private CreateTableMapper createTableMapper;

    @XxlJob("createTableJobHandler")
    public void createTableJobHandler() throws Exception {
        String date = LocalDate.now().plusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        XxlJobHelper.log("## 开始创建日增量数据表，日期: {}...", date);

        if (tableShards > 0) {
            for (int hashKey = 0; hashKey < tableShards; hashKey++) {
                String tableNameSuffix = TableConstants.buildTableNameSuffix(date, hashKey);
                createTableMapper.createDataAlignFollowingCountTempTable(tableNameSuffix);
                createTableMapper.createDataAlignFansCountTempTable(tableNameSuffix);
                createTableMapper.createDataAlignNoteCollectCountTempTable(tableNameSuffix);
                createTableMapper.createDataAlignUserCollectCountTempTable(tableNameSuffix);
                createTableMapper.createDataAlignUserLikeCountTempTable(tableNameSuffix);
                createTableMapper.createDataAlignNoteLikeCountTempTable(tableNameSuffix);
                createTableMapper.createDataAlignNotePublishCountTempTable(tableNameSuffix);
            }
        }

        XxlJobHelper.log("## 结束创建日增量数据表，日期: {}...", date);
    }
}
