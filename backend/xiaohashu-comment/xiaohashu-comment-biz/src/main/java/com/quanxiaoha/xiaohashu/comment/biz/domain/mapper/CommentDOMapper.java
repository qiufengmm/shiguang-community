package com.quanxiaoha.xiaohashu.comment.biz.domain.mapper;

import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentDO;
import com.quanxiaoha.xiaohashu.comment.biz.model.bo.CommentHeatBO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface CommentDOMapper {
    int deleteByPrimaryKey(Long id);

    int deleteByParentId(Long commentId);

    int deleteByIds(@Param("commentIds") List<Long> commentIds);

    int insert(CommentDO record);

    int insertSelective(CommentDO record);

    CommentDO selectByPrimaryKey(Long id);

    CommentDO selectByPrimaryKeyForUpdate(Long id);

    CommentDO selectByReplyCommentId(Long commentId);

    int updateByPrimaryKeySelective(CommentDO record);

    int updateByPrimaryKey(CommentDO record);

    List<CommentDO> selectByCommentIds(@Param("commentIds") List<Long> commentIds);

    List<CommentDO> selectCommentCountByIds(@Param("commentIds") List<Long> commentIds);

    int batchInsert(@Param("commentDOS") List<CommentDO> commentDOS);

    int insertIfAbsent(CommentDO record);

    List<CommentDO> selectDeleteTreeByRootCommentId(@Param("commentId") Long commentId);

    int updateChildCommentTotal(@Param("parentId") Long parentId, @Param("count") int count);

    int batchUpdateHeatByCommentIds(@Param("commentIds") List<Long> commentIds,
                                    @Param("commentHeatBOS") List<CommentHeatBO> commentHeatBOS);

    CommentDO selectEarliestByParentId(@Param("parentId") Long parentId);

    int updateFirstReplyCommentIdByPrimaryKey(@Param("firstReplyCommentId") Long firstReplyCommentId,
                                              @Param("id") Long id);

    List<CommentDO> selectPageList(@Param("noteId") Long noteId,
                                   @Param("offset") long offset,
                                   @Param("pageSize") long pageSize);

    List<CommentDO> selectTwoLevelCommentByIds(@Param("commentIds") List<Long> commentIds);

    List<CommentDO> selectHeatComments(@Param("noteId") Long noteId);

    Long selectChildCommentTotalById(Long commentId);

    List<CommentDO> selectChildPageList(@Param("parentId") Long parentId,
                                        @Param("offset") long offset,
                                        @Param("pageSize") long pageSize);

    List<CommentDO> selectChildCommentsByParentIdAndLimit(@Param("parentId") Long parentId,
                                                          @Param("limit") int limit);
}
