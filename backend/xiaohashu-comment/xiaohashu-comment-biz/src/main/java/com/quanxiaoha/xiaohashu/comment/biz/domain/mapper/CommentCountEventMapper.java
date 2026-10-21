package com.quanxiaoha.xiaohashu.comment.biz.domain.mapper;

import org.apache.ibatis.annotations.Param;

public interface CommentCountEventMapper {
    Integer selectAppliedDelta(@Param("eventId") String eventId, @Param("counterType") String counterType);
}
