package com.quanxiaoha.xiaohashu.count.biz.domain.mapper;

import com.quanxiaoha.xiaohashu.count.biz.domain.dataobject.UserCountDO;
import org.apache.ibatis.annotations.Param;

public interface UserCountDOMapper {
    int deleteByPrimaryKey(Long id);

    int insert(UserCountDO record);

    int insertSelective(UserCountDO record);

    UserCountDO selectByPrimaryKey(Long id);

    /**
     * 根据用户 ID 查询
     */
    UserCountDO selectByUserId(Long userId);

    int updateByPrimaryKeySelective(UserCountDO record);

    int updateByPrimaryKey(UserCountDO record);

    /**
     * 添加或更新粉丝总数
     */
    int insertOrUpdateFansTotalByUserId(@Param("count") Integer count, @Param("userId") Long userId);

    /**
     * 添加或更新关注总数
     */
    int insertOrUpdateFollowingTotalByUserId(@Param("count") Integer count, @Param("userId") Long userId);

    int insertOrUpdateLikeTotalByUserId(@Param("count") Integer count, @Param("userId") Long userId);

    int insertOrUpdateCollectTotalByUserId(@Param("count") Integer count, @Param("userId") Long userId);

    int insertOrUpdateNoteTotalByUserId(@Param("count") Long count, @Param("userId") Long userId);

    int setNoteTotalByUserId(@Param("count") Long count, @Param("userId") Long userId);
}
