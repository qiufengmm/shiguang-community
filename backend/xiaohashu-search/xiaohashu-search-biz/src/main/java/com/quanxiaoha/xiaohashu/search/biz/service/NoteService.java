package com.quanxiaoha.xiaohashu.search.biz.service;

import com.quanxiaoha.framework.common.response.PageResponse;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.dto.RebuildNoteDocumentReqDTO;
import com.quanxiaoha.xiaohashu.search.biz.model.vo.SearchNoteReqVO;
import com.quanxiaoha.xiaohashu.search.biz.model.vo.SearchNoteRspVO;

public interface NoteService {
    PageResponse<SearchNoteRspVO> searchNote(SearchNoteReqVO searchNoteReqVO);

    Response<Long> rebuildDocument(RebuildNoteDocumentReqDTO request);
}
