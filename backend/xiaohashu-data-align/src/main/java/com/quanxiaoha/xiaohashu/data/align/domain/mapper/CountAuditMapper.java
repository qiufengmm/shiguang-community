package com.quanxiaoha.xiaohashu.data.align.domain.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface CountAuditMapper {
    List<String> selectMarkerTables(@Param("tablePrefix") String tablePrefix);

    List<CountAuditMarker> selectBatch(@Param("tableName") String tableName,
                                       @Param("entityColumn") String entityColumn,
                                       @Param("lastRowId") long lastRowId,
                                       @Param("batchSize") int batchSize);

    Long selectSourceCount(@Param("metric") String metric, @Param("entityId") long entityId);

    Long selectStoredCount(@Param("metric") String metric, @Param("entityId") long entityId);

    int ensureCounterRow(@Param("metric") String metric, @Param("entityId") long entityId);

    Long lockCounterRow(@Param("metric") String metric, @Param("entityId") long entityId);

    int updateStoredCount(@Param("metric") String metric, @Param("entityId") long entityId,
                          @Param("total") long total);

    Long selectMarkerRowCount(@Param("tableName") String tableName);
}
