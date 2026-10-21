package com.quanxiaoha.xiaohashu.note.biz.domain.dataobject;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteContentSagaDO {
    private Long id;
    private String operationId;
    private Long noteId;
    private String operationType;
    private String contentUuid;
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
    private LocalDateTime completedTime;
}
