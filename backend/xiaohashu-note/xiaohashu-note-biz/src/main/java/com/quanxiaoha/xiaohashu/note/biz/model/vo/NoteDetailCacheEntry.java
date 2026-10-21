package com.quanxiaoha.xiaohashu.note.biz.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 笔记详情内部缓存包装，公开接口仍只返回 FindNoteDetailRspVO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteDetailCacheEntry {

    private Boolean contentRequired;

    private FindNoteDetailRspVO detail;

    public boolean canServe() {
        if (detail == null || contentRequired == null) {
            return false;
        }
        return !contentRequired || (detail.getContent() != null && !detail.getContent().isBlank());
    }
}
