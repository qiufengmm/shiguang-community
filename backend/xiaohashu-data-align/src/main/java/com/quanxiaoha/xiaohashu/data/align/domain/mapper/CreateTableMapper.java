package com.quanxiaoha.xiaohashu.data.align.domain.mapper;

/**
 * 自动创建日增量表
 */
public interface CreateTableMapper {

    void createDataAlignFollowingCountTempTable(String tableNameSuffix);

    void createDataAlignFansCountTempTable(String tableNameSuffix);

    void createDataAlignNoteCollectCountTempTable(String tableNameSuffix);

    void createDataAlignUserCollectCountTempTable(String tableNameSuffix);

    void createDataAlignUserLikeCountTempTable(String tableNameSuffix);

    void createDataAlignNoteLikeCountTempTable(String tableNameSuffix);

    void createDataAlignNotePublishCountTempTable(String tableNameSuffix);
}
