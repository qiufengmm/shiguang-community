package com.quanxiaoha.xiaohashu.data.align.domain.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * 清理计数二级消费者的幂等日志（{@code t_count_consume_log}）。
 * <p>
 * 这张表由 xiaohashu-count 写入，data-align 只负责按保留期删除，
 * 避免它随消息量无限增长。
 */
public interface CountConsumeLogCleanMapper {

    int deleteExpired(@Param("retentionDays") int retentionDays,
                      @Param("limit") int limit);
}
