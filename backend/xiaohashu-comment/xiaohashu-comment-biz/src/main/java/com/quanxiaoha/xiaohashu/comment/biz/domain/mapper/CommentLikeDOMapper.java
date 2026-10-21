package com.quanxiaoha.xiaohashu.comment.biz.domain.mapper;

import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentLikeDO;
import com.quanxiaoha.xiaohashu.comment.biz.model.dto.LikeUnlikeCommentMqDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface CommentLikeDOMapper {
    int deleteByPrimaryKey(Long id);

    int insert(CommentLikeDO record);

    int insertSelective(CommentLikeDO record);

    CommentLikeDO selectByPrimaryKey(Long id);

    int updateByPrimaryKeySelective(CommentLikeDO record);

    int updateByPrimaryKey(CommentLikeDO record);

    int selectCountByUserIdAndCommentId(@Param("userId") Long userId,
                                        @Param("commentId") Long commentId);

    List<CommentLikeDO> selectByUserId(@Param("userId") Long userId);

    int batchDelete(@Param("unlikes") List<LikeUnlikeCommentMqDTO> unlikes);

    int batchInsert(@Param("likes") List<LikeUnlikeCommentMqDTO> likes);
}
