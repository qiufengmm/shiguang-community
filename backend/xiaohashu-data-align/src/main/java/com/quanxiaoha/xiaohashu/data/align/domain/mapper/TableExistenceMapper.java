package com.quanxiaoha.xiaohashu.data.align.domain.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 查询日增量标记表是否已存在
 */
public interface TableExistenceMapper {

    /**
     * 返回入参中真实存在的表名，用于启动自检时算出缺失清单。
     */
    List<String> selectExistingTableNames(@Param("tableNames") List<String> tableNames);
}
