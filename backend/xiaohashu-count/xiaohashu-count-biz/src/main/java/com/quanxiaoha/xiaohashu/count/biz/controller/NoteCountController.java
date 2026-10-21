package com.quanxiaoha.xiaohashu.count.biz.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.quanxiaoha.framework.biz.operationlog.aspect.ApiOperationLog;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.biz.enums.ResponseCodeEnum;
import com.quanxiaoha.xiaohashu.count.biz.service.NoteCountService;
import com.quanxiaoha.xiaohashu.count.dto.FindNoteCountsByIdRspDTO;
import com.quanxiaoha.xiaohashu.count.dto.FindNoteCountsByIdsReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/count")
@Slf4j
public class NoteCountController {

    @Resource
    private NoteCountService noteCountService;

    @PostMapping("/notes/data")
    @ApiOperationLog(description = "批量获取笔记计数数据")
    @SentinelResource(value = "findNotesCountData4Controller", blockHandler = "blockHandler4findNotesCountData")
    public Response<List<FindNoteCountsByIdRspDTO>> findNotesCountData(
            @Validated @RequestBody FindNoteCountsByIdsReqDTO request) {
        return noteCountService.findNotesCountData(request);
    }

    public Response<List<FindNoteCountsByIdRspDTO>> blockHandler4findNotesCountData(
            FindNoteCountsByIdsReqDTO request, BlockException blockException) {
        throw new BizException(ResponseCodeEnum.FLOW_LIMIT);
    }
}
