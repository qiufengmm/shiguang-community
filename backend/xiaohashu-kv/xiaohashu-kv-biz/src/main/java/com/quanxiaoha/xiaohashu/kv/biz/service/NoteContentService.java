package com.quanxiaoha.xiaohashu.kv.biz.service;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.kv.dto.req.AddNoteContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.DeleteNoteContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.FindNoteContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.rsp.FindNoteContentRspDTO;

public interface NoteContentService {

    Response<?> addNoteContent(AddNoteContentReqDTO addNoteContentReqDTO);

    Response<FindNoteContentRspDTO> findNoteContent(FindNoteContentReqDTO findNoteContentReqDTO);

    Response<?> deleteNoteContent(DeleteNoteContentReqDTO deleteNoteContentReqDTO);
}
