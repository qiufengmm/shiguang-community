package com.quanxiaoha.xiaohashu.kv.biz.service;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.kv.dto.req.BatchAddCommentContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.BatchFindCommentContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.DeleteCommentContentReqDTO;

public interface CommentContentService {

    Response<?> batchAddCommentContent(BatchAddCommentContentReqDTO batchAddCommentContentReqDTO);

    Response<?> batchFindCommentContent(BatchFindCommentContentReqDTO batchFindCommentContentReqDTO);

    Response<?> deleteCommentContent(DeleteCommentContentReqDTO deleteCommentContentReqDTO);
}
