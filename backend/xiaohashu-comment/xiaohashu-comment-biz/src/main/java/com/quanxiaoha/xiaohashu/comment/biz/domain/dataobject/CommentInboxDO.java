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
public class CommentInboxDO {
    private Long id;
    private String eventKey;
    private String eventType;
    private String payload;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    private String lastError;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private Long leaseVersion;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
    private LocalDateTime appliedTime;
}
