package com.quanxiaoha.xiaohashu.user.biz.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户主页信息响应
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FindUserProfileRspVO {

    /** 用户 ID */
    private Long userId;

    /** 头像 */
    private String avatar;

    /** 昵称 */
    private String nickname;

    /** 拾光号 ID（对外展示名，字段名沿用 xiaohashuId） */
    private String xiaohashuId;

    /** 性别 */
    private Integer sex;

    /** 年龄 */
    private Integer age;

    /** 个人介绍 */
    private String introduction;

    /** 关注数 */
    private String followingTotal;

    /** 粉丝数 */
    private String fansTotal;

    /** 点赞与收藏总数 */
    private String likeAndCollectTotal;

    /** 当前发布笔记数 */
    private String noteTotal;

    /** 当前获得点赞数 */
    private String likeTotal;

    /** 当前获得收藏数 */
    private String collectTotal;
}
