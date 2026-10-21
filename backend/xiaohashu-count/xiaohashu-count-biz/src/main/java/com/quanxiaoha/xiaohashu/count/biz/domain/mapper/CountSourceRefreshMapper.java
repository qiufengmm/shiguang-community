package com.quanxiaoha.xiaohashu.count.biz.domain.mapper;

import org.apache.ibatis.annotations.Param;

/** SQL operations used to refresh counters from their MySQL source tables. */
public interface CountSourceRefreshMapper {

    int ensureUserCountRow(@Param("userId") long userId);

    int ensureNoteCountRow(@Param("noteId") long noteId);

    Long lockUserCountRow(@Param("userId") long userId);

    Long lockNoteCountRow(@Param("noteId") long noteId);

    Long selectFollowingTotal(@Param("userId") long userId);

    Long selectFansTotal(@Param("userId") long userId);

    Long selectNoteLikeTotal(@Param("noteId") long noteId);

    Long selectNoteCollectTotal(@Param("noteId") long noteId);

    Long selectUserLikeTotal(@Param("userId") long userId);

    Long selectUserCollectTotal(@Param("userId") long userId);

    Long selectNoteCreatorId(@Param("noteId") long noteId);

    int updateFollowingTotal(@Param("userId") long userId, @Param("total") long total);

    int updateFansTotal(@Param("userId") long userId, @Param("total") long total);

    int updateNoteLikeTotal(@Param("noteId") long noteId, @Param("total") long total);

    int updateNoteCollectTotal(@Param("noteId") long noteId, @Param("total") long total);

    int updateUserLikeTotal(@Param("userId") long userId, @Param("total") long total);

    int updateUserCollectTotal(@Param("userId") long userId, @Param("total") long total);
}
