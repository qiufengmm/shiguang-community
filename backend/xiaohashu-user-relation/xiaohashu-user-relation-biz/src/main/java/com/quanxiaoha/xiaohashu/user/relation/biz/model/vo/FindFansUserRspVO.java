package com.quanxiaoha.xiaohashu.user.relation.biz.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 查询粉丝列表
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FindFansUserRspVO {

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 头像
     */
    private String avatar;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 粉丝总数
     */
    private Long fansTotal;

    /**
     * 笔记总数
     */
    private Long noteTotal;

}
