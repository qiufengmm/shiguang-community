package com.quanxiaoha.xiaohashu.comment.biz.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FindChildCommentItemRspVO {

    private Long commentId;

    private Long userId;

    private String avatar;

    private String nickname;

    private String content;

    private String imageUrl;

    private String createTime;

    private Long likeTotal;

    private String replyUserName;

    private Long replyUserId;
}
