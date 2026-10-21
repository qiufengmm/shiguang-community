package com.quanxiaoha.xiaohashu.comment.biz.service;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.framework.common.response.PageResponse;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.FindChildCommentItemRspVO;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.FindChildCommentPageListReqVO;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.FindCommentItemRspVO;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.FindCommentPageListReqVO;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.LikeCommentReqVO;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.UnLikeCommentReqVO;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.PublishCommentReqVO;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.DeleteCommentReqVO;

public interface CommentService {

    Response<?> publishComment(PublishCommentReqVO publishCommentReqVO);

    PageResponse<FindCommentItemRspVO> findCommentPageList(FindCommentPageListReqVO findCommentPageListReqVO);

    PageResponse<FindChildCommentItemRspVO> findChildCommentPageList(
            FindChildCommentPageListReqVO findChildCommentPageListReqVO);

    Response<?> likeComment(LikeCommentReqVO likeCommentReqVO);

    Response<?> unlikeComment(UnLikeCommentReqVO unLikeCommentReqVO);

    Response<?> deleteComment(DeleteCommentReqVO deleteCommentReqVO);

    void deleteCommentLocalCache(Long commentId);
}
