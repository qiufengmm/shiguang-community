package com.quanxiaoha.xiaohashu.kv.biz.controller;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.kv.biz.service.NoteContentService;
import com.quanxiaoha.xiaohashu.kv.dto.req.AddNoteContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.DeleteNoteContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.FindNoteContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.rsp.FindNoteContentRspDTO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/kv")
public class NoteContentController {

    private final NoteContentService noteContentService;

    public NoteContentController(NoteContentService noteContentService) {
        this.noteContentService = noteContentService;
    }

    @PostMapping("/note/content/add")
    public Response<?> addNoteContent(@Valid @RequestBody AddNoteContentReqDTO addNoteContentReqDTO) {
        return noteContentService.addNoteContent(addNoteContentReqDTO);
    }

    @PostMapping("/note/content/find")
    public Response<FindNoteContentRspDTO> findNoteContent(
            @Valid @RequestBody FindNoteContentReqDTO findNoteContentReqDTO) {
        return noteContentService.findNoteContent(findNoteContentReqDTO);
    }

    @PostMapping("/note/content/delete")
    public Response<?> deleteNoteContent(@Valid @RequestBody DeleteNoteContentReqDTO deleteNoteContentReqDTO) {
        return noteContentService.deleteNoteContent(deleteNoteContentReqDTO);
    }
}
