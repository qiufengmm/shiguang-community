package com.quanxiaoha.xiaohashu.user.biz.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 获取用户主页信息请求
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FindUserProfileReqVO {

    /** 用户 ID */
    private Long userId;
}
