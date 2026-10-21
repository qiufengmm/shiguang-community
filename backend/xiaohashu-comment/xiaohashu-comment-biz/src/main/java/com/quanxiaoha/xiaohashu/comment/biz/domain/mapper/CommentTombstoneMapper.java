package com.quanxiaoha.xiaohashu.comment.biz.domain.mapper;

import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentTombstoneDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface CommentTombstoneMapper {
    int existsByCommentId(@Param("commentId") Long commentId);

    CommentTombstoneDO selectByCommentIdForUpdate(@Param("commentId") Long commentId);

    int insertIfAbsent(CommentTombstoneDO record);

    List<CommentTombstoneDO> selectByDeleteRootId(@Param("deleteRootId") Long deleteRootId);

    List<CommentTombstoneDO> selectPendingContentCleanup(@Param("limit") int limit);

    int markContentCleaned(@Param("commentId") Long commentId);
}
