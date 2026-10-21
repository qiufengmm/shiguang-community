package com.quanxiaoha.xiaohashu.comment.biz.domain.mapper;

import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentInboxDO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface CommentInboxMapper {
    int insertIfAbsent(CommentInboxDO record);

    CommentInboxDO selectByEventKey(@Param("eventKey") String eventKey);

    List<CommentInboxDO> selectClaimable(@Param("now") LocalDateTime now, @Param("limit") int limit);

    int acquireLease(@Param("id") Long id, @Param("owner") String owner,
                     @Param("leaseUntil") LocalDateTime leaseUntil, @Param("now") LocalDateTime now);

    int markApplied(@Param("id") Long id, @Param("owner") String owner,
                    @Param("leaseVersion") Long leaseVersion, @Param("appliedTime") LocalDateTime appliedTime);

    int scheduleRetry(@Param("id") Long id, @Param("owner") String owner,
                      @Param("leaseVersion") Long leaseVersion, @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                      @Param("lastError") String lastError, @Param("updatedTime") LocalDateTime updatedTime);

    int scrubAppliedPayloadsBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
