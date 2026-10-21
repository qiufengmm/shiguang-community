package com.quanxiaoha.xiaohashu.count.biz.domain.mapper;

import org.apache.ibatis.annotations.Param;

public interface NotePublishCountReconciliationMapper {
    long countActiveNotesByUserId(@Param("userId") Long userId);
}
