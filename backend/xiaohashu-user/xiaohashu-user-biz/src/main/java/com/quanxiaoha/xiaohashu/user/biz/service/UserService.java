package com.quanxiaoha.xiaohashu.user.biz.service;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.user.biz.model.vo.FindUserProfileReqVO;
import com.quanxiaoha.xiaohashu.user.biz.model.vo.FindUserProfileRspVO;
import com.quanxiaoha.xiaohashu.user.biz.model.vo.UpdateUserInfoReqVO;
import com.quanxiaoha.xiaohashu.user.dto.req.*;
import com.quanxiaoha.xiaohashu.user.dto.resp.FindUserByIdRspDTO;
import com.quanxiaoha.xiaohashu.user.dto.resp.FindUserByPhoneRspDTO;

import java.util.List;

public interface UserService {

    Response<?> updateUserInfo(UpdateUserInfoReqVO updateUserInfoReqVO);

    Response<Long> register(RegisterUserReqDTO registerUserReqDTO);

    Response<FindUserByPhoneRspDTO> findByPhone(FindUserByPhoneReqDTO findUserByPhoneReqDTO);

    Response<?> updatePassword(UpdateUserPasswordReqDTO updateUserPasswordReqDTO);

    /**
     * 根据用户 ID 查询用户信息
     *
     * @param findUserByIdReqDTO
     * @return
     */
    Response<FindUserByIdRspDTO> findById(FindUserByIdReqDTO findUserByIdReqDTO);

    /**
     * 批量根据用户 ID 查询用户信息
     *
     * @param findUsersByIdsReqDTO
     * @return
     */
    Response<List<FindUserByIdRspDTO>> findByIds(FindUsersByIdsReqDTO findUsersByIdsReqDTO);

    Response<FindUserProfileRspVO> findUserProfile(FindUserProfileReqVO findUserProfileReqVO);
}
