package com.quanxiaoha.xiaohashu.comment.biz.domain.mapper;

import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentOutboxDO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface CommentOutboxMapper {
    int insertIfAbsent(CommentOutboxDO record);

    CommentOutboxDO selectByEventKey(@Param("eventKey") String eventKey);

    int existsByEventKey(@Param("eventKey") String eventKey);

    List<CommentOutboxDO> selectClaimable(@Param("now") LocalDateTime now,
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

    int scrubSentPayloadsBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
