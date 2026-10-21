package com.quanxiaoha.xiaohashu.note.biz.domain.mapper;

import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteOperateOutboxDO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface NoteOperateOutboxMapper {
    int insertIfAbsent(NoteOperateOutboxDO record);

    List<NoteOperateOutboxDO> selectClaimable(@Param("now") LocalDateTime now,
                                              @Param("limit") int limit);

    int acquireLease(@Param("id") Long id,
                     @Param("owner") String owner,
                     @Param("leaseUntil") LocalDateTime leaseUntil,
                     @Param("now") LocalDateTime now);

    int markSent(@Param("id") Long id,
                 @Param("owner") String owner,
                 @Param("leaseVersion") Long leaseVersion,
                 @Param("sentTime") LocalDateTime sentTime);

    int scheduleRetry(@Param("id") Long id,
                      @Param("owner") String owner,
                      @Param("leaseVersion") Long leaseVersion,
                      @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                      @Param("lastError") String lastError,
                      @Param("updatedTime") LocalDateTime updatedTime);
}
