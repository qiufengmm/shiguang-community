package com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentTombstoneDO {
    private Long commentId;
    private Long deleteRootId;
    private Long noteId;
    private Integer level;
    private Long parentId;
    private String contentUuid;
    private Boolean contentEmpty;
    private LocalDateTime createTime;
    private String contentCleanupStatus;
    private LocalDateTime deletedTime;
}
