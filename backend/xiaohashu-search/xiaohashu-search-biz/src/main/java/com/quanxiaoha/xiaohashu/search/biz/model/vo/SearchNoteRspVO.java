package com.quanxiaoha.xiaohashu.search.biz.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SearchNoteRspVO {
    private Long noteId;
    private String cover;
    private String title;
    private String highlightTitle;
    private String avatar;
    private String nickname;
    private String updateTime;
    private String likeTotal;
    private String commentTotal;
    private String collectTotal;
}
