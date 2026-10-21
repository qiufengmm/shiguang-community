package com.quanxiaoha.xiaohashu.kv.biz.service.impl;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.kv.biz.domain.dataobject.NoteContentDO;
import com.quanxiaoha.xiaohashu.kv.biz.enums.ResponseCodeEnum;
import com.quanxiaoha.xiaohashu.kv.biz.service.NoteContentService;
import com.quanxiaoha.xiaohashu.kv.biz.service.NoteContentPersistenceService;
import com.quanxiaoha.xiaohashu.kv.dto.req.AddNoteContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.DeleteNoteContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.FindNoteContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.rsp.FindNoteContentRspDTO;
import com.quanxiaoha.framework.common.exception.BizException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class NoteContentServiceImpl implements NoteContentService {

    private final NoteContentPersistenceService noteContentPersistenceService;

    public NoteContentServiceImpl(NoteContentPersistenceService noteContentPersistenceService) {
        this.noteContentPersistenceService = noteContentPersistenceService;
    }

    @Override
    public Response<?> addNoteContent(AddNoteContentReqDTO addNoteContentReqDTO) {
        UUID id = UUID.fromString(addNoteContentReqDTO.getUuid());
        boolean saved = noteContentPersistenceService.saveIfNotDeleted(id, addNoteContentReqDTO.getContent());
        if (!saved) {
            throw new BizException(ResponseCodeEnum.NOTE_CONTENT_VERSION_RETIRED);
        }
        return Response.success();
    }

    @Override
    public Response<FindNoteContentRspDTO> findNoteContent(FindNoteContentReqDTO findNoteContentReqDTO) {
        UUID contentUuid = UUID.fromString(findNoteContentReqDTO.getUuid());
        NoteContentDO noteContent = noteContentPersistenceService.findLive(contentUuid)
                .orElseThrow(() -> new BizException(ResponseCodeEnum.NOTE_CONTENT_NOT_FOUND));

        FindNoteContentRspDTO response = FindNoteContentRspDTO.builder()
                .uuid(noteContent.getId())
                .content(noteContent.getContent())
                .build();
        return Response.success(response);
    }

    @Override
    public Response<?> deleteNoteContent(DeleteNoteContentReqDTO deleteNoteContentReqDTO) {
        noteContentPersistenceService.tombstone(UUID.fromString(deleteNoteContentReqDTO.getUuid()));
        return Response.success();
    }
}
