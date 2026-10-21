package com.quanxiaoha.xiaohashu.count.biz.domain.mapper;

import org.apache.ibatis.annotations.Param;

public interface NotePublishCountEventMapper {
    int insertIfAbsent(@Param("eventId") String eventId,
                       @Param("userId") Long userId,
                       @Param("noteId") Long noteId,
                       @Param("operationType") Integer operationType);

    Long selectUserIdByEventId(@Param("eventId") String eventId);

    int insertOutboxEventsAsApplied(@Param("userId") Long userId);
}
