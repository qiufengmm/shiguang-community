package com.quanxiaoha.xiaohashu.note.biz.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 笔记卡片展示数据
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class NoteItemRspVO {

    private Long noteId;
    private Integer type;
    private String cover;
    private String videoUri;
    private String title;
    private Long creatorId;
    private String nickname;
    private String avatar;
    private String likeTotal;

    /**
     * 当前登录用户是否已点赞
     */
    private Boolean isLiked;
}
