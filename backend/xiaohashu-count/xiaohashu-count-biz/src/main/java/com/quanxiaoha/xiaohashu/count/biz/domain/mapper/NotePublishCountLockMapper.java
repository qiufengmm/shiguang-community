package com.quanxiaoha.xiaohashu.count.biz.domain.mapper;

import org.apache.ibatis.annotations.Param;

public interface NotePublishCountLockMapper {
    int ensureLockRow(@Param("userId") Long userId);

    Long lockByUserId(@Param("userId") Long userId);
}
