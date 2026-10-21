package com.quanxiaoha.xiaohashu.count.biz.domain.mapper;

import org.apache.ibatis.annotations.Param;

public interface CommentCountEventMapper {
    int insertIfAbsent(@Param("eventId") String eventId,
                       @Param("counterType") String counterType,
                       @Param("commentId") Long commentId,
                       @Param("noteId") Long noteId,
                       @Param("parentId") Long parentId,
                       @Param("appliedDelta") int appliedDelta);

    Integer selectAppliedDeltaForUpdate(@Param("eventId") String eventId,
                                        @Param("counterType") String counterType);

    int updateAppliedDelta(@Param("eventId") String eventId,
                           @Param("counterType") String counterType,
                           @Param("appliedDelta") int appliedDelta);

    Long selectTombstoneForUpdate(@Param("commentId") Long commentId);
}
