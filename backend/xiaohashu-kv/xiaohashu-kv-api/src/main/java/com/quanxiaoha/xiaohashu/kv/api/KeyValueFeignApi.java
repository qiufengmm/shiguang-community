package com.quanxiaoha.xiaohashu.kv.api;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.kv.constant.ApiConstants;
import com.quanxiaoha.xiaohashu.kv.dto.req.AddNoteContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.BatchAddCommentContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.BatchFindCommentContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.DeleteNoteContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.DeleteCommentContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.FindNoteContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.rsp.FindCommentContentRspDTO;
import com.quanxiaoha.xiaohashu.kv.dto.rsp.FindNoteContentRspDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@FeignClient(name = ApiConstants.SERVICE_NAME)
public interface KeyValueFeignApi {

    String PREFIX = "/kv";

    @PostMapping(PREFIX + "/note/content/add")
    Response<?> addNoteContent(@RequestBody AddNoteContentReqDTO addNoteContentReqDTO);

    @PostMapping(PREFIX + "/note/content/find")
    Response<FindNoteContentRspDTO> findNoteContent(@RequestBody FindNoteContentReqDTO findNoteContentReqDTO);

    @PostMapping(PREFIX + "/note/content/delete")
    Response<?> deleteNoteContent(@RequestBody DeleteNoteContentReqDTO deleteNoteContentReqDTO);

    @PostMapping(PREFIX + "/comment/content/batchAdd")
    Response<?> batchAddCommentContent(@RequestBody BatchAddCommentContentReqDTO batchAddCommentContentReqDTO);

    @PostMapping(PREFIX + "/comment/content/batchFind")
    Response<List<FindCommentContentRspDTO>> batchFindCommentContent(
            @RequestBody BatchFindCommentContentReqDTO batchFindCommentContentReqDTO);

    @PostMapping(PREFIX + "/comment/content/delete")
    Response<?> deleteCommentContent(@RequestBody DeleteCommentContentReqDTO deleteCommentContentReqDTO);
}
