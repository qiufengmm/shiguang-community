package com.quanxiaoha.xiaohashu.note.biz.domain.mapper;

import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteContentSagaDO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface NoteContentSagaMapper {
    int insertIfAbsent(NoteContentSagaDO saga);

    NoteContentSagaDO selectByOperationIdForUpdate(String operationId);

    NoteContentSagaDO selectByOperationId(String operationId);

    Long selectActiveLeaseForUpdate(@Param("id") Long id,
                                    @Param("owner") String owner,
                                    @Param("leaseVersion") Long leaseVersion);

    int upsertCompensationPending(NoteContentSagaDO saga);

    List<NoteContentSagaDO> selectClaimable(@Param("now") LocalDateTime now,
                                            @Param("limit") int limit);

    int acquireLease(@Param("id") Long id,
                     @Param("owner") String owner,
                     @Param("leaseUntil") LocalDateTime leaseUntil,
                     @Param("now") LocalDateTime now);

    int markCompleted(@Param("id") Long id,
                      @Param("owner") String owner,
                      @Param("leaseVersion") Long leaseVersion,
                      @Param("now") LocalDateTime now);

    int markDeletePending(@Param("id") Long id,
                          @Param("owner") String owner,
                          @Param("leaseVersion") Long leaseVersion,
                          @Param("contentUuid") String contentUuid,
                          @Param("now") LocalDateTime now);

    int markCompensationPending(@Param("id") Long id,
                                @Param("owner") String owner,
                                @Param("leaseVersion") Long leaseVersion,
                                @Param("contentUuid") String contentUuid,
                                @Param("error") String error,
                                @Param("now") LocalDateTime now);

    int markRolledBack(@Param("id") Long id,
                       @Param("owner") String owner,
                       @Param("leaseVersion") Long leaseVersion,
                       @Param("now") LocalDateTime now);

    int scheduleRetry(@Param("id") Long id,
                      @Param("owner") String owner,
                      @Param("leaseVersion") Long leaseVersion,
                      @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                      @Param("error") String error,
                      @Param("now") LocalDateTime now);
}
