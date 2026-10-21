package com.quanxiaoha.xiaohashu.kv.dto.rsp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 评论正文查询结果。 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FindCommentContentRspDTO {

    private String contentId;

    private String content;
}
