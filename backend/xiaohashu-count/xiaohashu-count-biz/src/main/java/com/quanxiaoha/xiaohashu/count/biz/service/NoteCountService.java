package com.quanxiaoha.xiaohashu.count.biz.service;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.dto.FindNoteCountsByIdRspDTO;
import com.quanxiaoha.xiaohashu.count.dto.FindNoteCountsByIdsReqDTO;

import java.util.List;

/** 笔记计数业务 */
public interface NoteCountService {

    Response<List<FindNoteCountsByIdRspDTO>> findNotesCountData(FindNoteCountsByIdsReqDTO request);
}
