package com.quanxiaoha.xiaohashu.note.biz.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 个人主页已发布笔记列表响应参数
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FindPublishedNoteListRspVO {

    private List<NoteItemRspVO> notes;
    private Long nextCursor;
}
