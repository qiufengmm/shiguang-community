package com.quanxiaoha.xiaohashu.count.biz.service;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.dto.FindUserCountsByIdReqDTO;
import com.quanxiaoha.xiaohashu.count.dto.FindUserCountsByIdRspDTO;

/**
 * 用户计数业务
 **/
public interface UserCountService {

    /**
     * 查询用户相关计数
     */
    Response<FindUserCountsByIdRspDTO> findUserCountData(FindUserCountsByIdReqDTO findUserCountsByIdReqDTO);
}
