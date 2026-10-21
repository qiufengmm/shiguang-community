package com.quanxiaoha.xiaohashu.count.biz.domain.mapper;

import com.quanxiaoha.xiaohashu.count.biz.domain.dataobject.NoteCountDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface NoteCountDOMapper {
    int deleteByPrimaryKey(Long id);

    int insert(NoteCountDO record);

    int insertSelective(NoteCountDO record);

    NoteCountDO selectByPrimaryKey(Long id);

    int updateByPrimaryKeySelective(NoteCountDO record);

    int updateByPrimaryKey(NoteCountDO record);

    int insertOrUpdateLikeTotalByNoteId(@Param("count") Integer count, @Param("noteId") Long noteId);

    int insertOrUpdateCollectTotalByNoteId(@Param("count") Integer count, @Param("noteId") Long noteId);

    int insertOrUpdateCommentTotalByNoteId(@Param("count") int count, @Param("noteId") Long noteId);

    List<NoteCountDO> selectByNoteIds(@Param("noteIds") List<Long> noteIds);
}
