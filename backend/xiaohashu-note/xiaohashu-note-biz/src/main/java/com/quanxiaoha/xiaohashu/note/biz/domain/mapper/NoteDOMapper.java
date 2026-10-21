package com.quanxiaoha.xiaohashu.note.biz.domain.mapper;

import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface NoteDOMapper {
    int deleteByPrimaryKey(Long id);

    int insert(NoteDO record);

    int insertSelective(NoteDO record);

    NoteDO selectByPrimaryKey(Long id);

    /** Recovery must inspect soft-deleted rows before deciding whether the body is still referenced. */
    NoteDO selectByPrimaryKeyIncludingDeleted(Long id);

    int updateByPrimaryKeySelective(NoteDO record);

    int updateByPrimaryKey(NoteDO record);

    /** Updates note metadata only if the content pointer still matches the value read before the edit. */
    int updateContentWithExpectedUuid(@Param("note") NoteDO note,
                                      @Param("expectedContentUuid") String expectedContentUuid);

    int updateVisibleOnlyMe(NoteDO record);

    int updateIsTop(NoteDO record);

    int selectCountByNoteId(Long noteId);

    /** 发布事务回查，包含逻辑删除记录，避免误判提交结果。 */
    String selectContentUuidByNoteId(Long noteId);

    Long selectCreatorIdByNoteId(Long noteId);

    /** 查询个人主页已发布笔记列表，按 ID 游标倒序分页。 */
    List<NoteDO> selectPublishedNoteListByUserIdAndCursor(@Param("creatorId") Long creatorId,
                                                          @Param("cursor") Long cursor);
}
