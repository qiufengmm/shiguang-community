package com.quanxiaoha.xiaohashu.note.biz.controller;

import com.quanxiaoha.framework.biz.operationlog.aspect.ApiOperationLog;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.DeleteNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.CollectNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.UnCollectNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindNoteDetailReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindNoteDetailRspVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindNoteIsLikedAndCollectedReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindNoteIsLikedAndCollectedRspVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindPublishedNoteListReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindPublishedNoteListRspVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.LikeNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.PublishNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.TopNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.UpdateNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.UpdateNoteVisibleOnlyMeReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.UnlikeNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.service.NoteService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 笔记
 **/
@RestController
@RequestMapping("/note")
@Slf4j
public class NoteController {

    @Resource
    private NoteService noteService;

    @PostMapping(value = "/publish")
    @ApiOperationLog(description = "笔记发布")
    public Response<?> publishNote(@Validated @RequestBody PublishNoteReqVO publishNoteReqVO) {
        return noteService.publishNote(publishNoteReqVO);
    }

    @PostMapping(value = "/detail")
    @ApiOperationLog(description = "笔记详情")
    public Response<FindNoteDetailRspVO> findNoteDetail(@Validated @RequestBody FindNoteDetailReqVO findNoteDetailReqVO) {
        return noteService.findNoteDetail(findNoteDetailReqVO);
    }

    @PostMapping(value = "/isLikedAndCollectedData")
    @ApiOperationLog(description = "获取当前用户是否点赞、收藏数据")
    public Response<FindNoteIsLikedAndCollectedRspVO> isLikedAndCollectedData(
            @Validated @RequestBody FindNoteIsLikedAndCollectedReqVO findNoteIsLikedAndCollectedReqVO) {
        return noteService.isLikedAndCollectedData(findNoteIsLikedAndCollectedReqVO);
    }

    @PostMapping(value = "/like")
    @ApiOperationLog(description = "点赞笔记")
    public Response<?> likeNote(@Validated @RequestBody LikeNoteReqVO likeNoteReqVO) {
        return noteService.likeNote(likeNoteReqVO);
    }

    @PostMapping(value = "/unlike")
    @ApiOperationLog(description = "取消点赞笔记")
    public Response<?> unlikeNote(@Validated @RequestBody UnlikeNoteReqVO unlikeNoteReqVO) {
        return noteService.unlikeNote(unlikeNoteReqVO);
    }

    @PostMapping(value = "/collect")
    @ApiOperationLog(description = "收藏笔记")
    public Response<?> collectNote(@Validated @RequestBody CollectNoteReqVO collectNoteReqVO) {
        return noteService.collectNote(collectNoteReqVO);
    }

    @PostMapping(value = "/uncollect")
    @ApiOperationLog(description = "取消收藏笔记")
    public Response<?> unCollectNote(@Validated @RequestBody UnCollectNoteReqVO unCollectNoteReqVO) {
        return noteService.unCollectNote(unCollectNoteReqVO);
    }

    @PostMapping(value = "/update")
    @ApiOperationLog(description = "笔记修改")
    public Response<?> updateNote(@Validated @RequestBody UpdateNoteReqVO updateNoteReqVO) {
        return noteService.updateNote(updateNoteReqVO);
    }

    @PostMapping(value = "/delete")
    @ApiOperationLog(description = "删除笔记")
    public Response<?> deleteNote(@Validated @RequestBody DeleteNoteReqVO deleteNoteReqVO) {
        return noteService.deleteNote(deleteNoteReqVO);
    }

    @PostMapping(value = "/visible/onlyme")
    @ApiOperationLog(description = "笔记仅对自己可见")
    public Response<?> visibleOnlyMe(@Validated @RequestBody UpdateNoteVisibleOnlyMeReqVO updateNoteVisibleOnlyMeReqVO) {
        return noteService.visibleOnlyMe(updateNoteVisibleOnlyMeReqVO);
    }

    @PostMapping(value = "/top")
    @ApiOperationLog(description = "置顶/取消置顶笔记")
    public Response<?> topNote(@Validated @RequestBody TopNoteReqVO topNoteReqVO) {
        return noteService.topNote(topNoteReqVO);
    }

    @PostMapping(value = "/published/list")
    @ApiOperationLog(description = "用户主页 - 已发布笔记列表")
    public Response<FindPublishedNoteListRspVO> findPublishedNoteList(
            @Validated @RequestBody FindPublishedNoteListReqVO findPublishedNoteListReqVO) {
        return noteService.findPublishedNoteList(findPublishedNoteListReqVO);
    }

}
