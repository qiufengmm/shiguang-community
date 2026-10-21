package com.quanxiaoha.xiaohashu.search.biz.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SearchUserRspVO {
    private Long userId;
    private String nickname;
    private String avatar;
    private String xiaohashuId;
    private Integer noteTotal;
    private String fansTotal;
    private String highlightNickname;
}
