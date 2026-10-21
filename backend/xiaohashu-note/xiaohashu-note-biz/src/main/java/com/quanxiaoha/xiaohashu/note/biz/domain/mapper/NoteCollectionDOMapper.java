package com.quanxiaoha.xiaohashu.note.biz.domain.mapper;

import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteCollectionDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface NoteCollectionDOMapper {
    int deleteByPrimaryKey(Long id);

    int insert(NoteCollectionDO record);

    int insertSelective(NoteCollectionDO record);

    NoteCollectionDO selectByPrimaryKey(Long id);

    int updateByPrimaryKeySelective(NoteCollectionDO record);

    int updateByPrimaryKey(NoteCollectionDO record);

    int selectCountByUserIdAndNoteId(@Param("userId") Long userId, @Param("noteId") Long noteId);

    int selectNoteIsCollected(@Param("userId") Long userId, @Param("noteId") Long noteId);

    List<NoteCollectionDO> selectByUserId(@Param("userId") Long userId);

    List<NoteCollectionDO> selectCollectedByUserIdAndLimit(@Param("userId") Long userId,
                                                           @Param("limit") int limit);

    int insertOrUpdate(NoteCollectionDO noteCollectionDO);

    int update2UnCollectByUserIdAndNoteId(NoteCollectionDO noteCollectionDO);
}
