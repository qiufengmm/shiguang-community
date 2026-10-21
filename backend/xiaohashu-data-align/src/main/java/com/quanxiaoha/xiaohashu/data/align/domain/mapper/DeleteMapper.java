package com.quanxiaoha.xiaohashu.data.align.domain.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 删除
 */
public interface DeleteMapper {

    /**
     * 日增量表：关注数计数变更 - 批量删除
     * @param tableNameSuffix
     * @param userIds
     */
    void batchDeleteDataAlignFollowingCountTempTable(@Param("tableNameSuffix") String tableNameSuffix,
                                                     @Param("userIds") List<Long> userIds);

    /**
     * 日增量表：笔记点赞数计数变更 - 批量删除
     * @param tableNameSuffix
     * @param noteIds
     */
    void batchDeleteDataAlignNoteLikeCountTempTable(@Param("tableNameSuffix") String tableNameSuffix,
                                                    @Param("noteIds") List<Long> noteIds);
}
