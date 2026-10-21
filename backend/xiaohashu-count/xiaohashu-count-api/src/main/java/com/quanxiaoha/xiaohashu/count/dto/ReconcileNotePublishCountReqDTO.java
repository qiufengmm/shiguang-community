package com.quanxiaoha.xiaohashu.count.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request to reconcile one user's published-note counter under the count service lock. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconcileNotePublishCountReqDTO {

    @NotNull
    private Long userId;
}
