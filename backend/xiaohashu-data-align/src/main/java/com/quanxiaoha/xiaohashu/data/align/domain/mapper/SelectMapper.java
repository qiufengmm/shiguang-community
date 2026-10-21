package com.quanxiaoha.xiaohashu.data.align.domain.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 查询
 */
public interface SelectMapper {


    /**
     * 日增量表：关注数计数变更 - 批量查询
     * @param tableNameSuffix
     * @param batchSize
     * @return
     */
    List<Long> selectBatchFromDataAlignFollowingCountTempTable(@Param("tableNameSuffix") String tableNameSuffix,
                                                               @Param("batchSize") int batchSize);

    /**
     * 查询 t_following 关注表，获取关注总数
     * @param userId
     * @return
     */
    int selectCountFromFollowingTableByUserId(long userId);

    /**
     * 日增量表：笔记点赞数计数变更 - 批量查询
     * @param tableNameSuffix
     * @param batchSize
     * @return
     */
    List<Long> selectBatchFromDataAlignNoteLikeCountTempTable(@Param("tableNameSuffix") String tableNameSuffix,
                                                              @Param("batchSize") int batchSize);

    /**
     * 查询 t_note_like 点赞表，获取笔记点赞总数
     * @param noteId
     * @return
     */
    int selectCountFromNoteLikeTableByUserId(long noteId);
}
