package com.quanxiaoha.xiaohashu.note.biz.service;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.DeleteNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.CollectNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.UnCollectNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindNoteDetailReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindNoteDetailRspVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindNoteIsLikedAndCollectedReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindNoteIsLikedAndCollectedRspVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.LikeNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.PublishNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.TopNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.UpdateNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.UpdateNoteVisibleOnlyMeReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.UnlikeNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindPublishedNoteListReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindPublishedNoteListRspVO;

/**
 * 笔记业务
 **/
public interface NoteService {

    /**
     * 笔记发布
     *
     * @param publishNoteReqVO
     * @return
     */
    Response<?> publishNote(PublishNoteReqVO publishNoteReqVO);

    /**
     * 笔记更新
     *
     * @param updateNoteReqVO
     * @return
     */
    Response<?> updateNote(UpdateNoteReqVO updateNoteReqVO);

    /**
     * 笔记详情
     *
     * @param findNoteDetailReqVO
     * @return
     */
    Response<FindNoteDetailRspVO> findNoteDetail(FindNoteDetailReqVO findNoteDetailReqVO);

    /**
     * 获取当前用户是否点赞、收藏笔记
     */
    Response<FindNoteIsLikedAndCollectedRspVO> isLikedAndCollectedData(
            FindNoteIsLikedAndCollectedReqVO findNoteIsLikedAndCollectedReqVO);

    /**
     * 点赞笔记
     */
    Response<?> likeNote(LikeNoteReqVO likeNoteReqVO);

    /**
     * 取消点赞笔记
     */
    Response<?> unlikeNote(UnlikeNoteReqVO unlikeNoteReqVO);

    /**
     * 收藏笔记
     */
    Response<?> collectNote(CollectNoteReqVO collectNoteReqVO);

    /**
     * 取消收藏笔记
     */
    Response<?> unCollectNote(UnCollectNoteReqVO unCollectNoteReqVO);

    /**
     * 删除本地笔记缓存
     *
     * @param noteId
     */
    void deleteNoteLocalCache(Long noteId);

    /**
     * 删除笔记
     *
     * @param deleteNoteReqVO
     * @return
     */
    Response<?> deleteNote(DeleteNoteReqVO deleteNoteReqVO);

    /**
     * 笔记仅对自己可见
     *
     * @param updateNoteVisibleOnlyMeReqVO
     * @return
     */
    Response<?> visibleOnlyMe(UpdateNoteVisibleOnlyMeReqVO updateNoteVisibleOnlyMeReqVO);

    /**
     * 笔记置顶 / 取消置顶
     *
     * @param topNoteReqVO
     * @return
     */
    Response<?> topNote(TopNoteReqVO topNoteReqVO);

    Response<FindPublishedNoteListRspVO> findPublishedNoteList(FindPublishedNoteListReqVO request);

}
