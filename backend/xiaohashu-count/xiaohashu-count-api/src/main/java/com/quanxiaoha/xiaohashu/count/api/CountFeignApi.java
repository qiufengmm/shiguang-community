package com.quanxiaoha.xiaohashu.count.api;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.constant.ApiConstants;
import com.quanxiaoha.xiaohashu.count.dto.FindNoteCountsByIdRspDTO;
import com.quanxiaoha.xiaohashu.count.dto.FindNoteCountsByIdsReqDTO;
import com.quanxiaoha.xiaohashu.count.dto.FindUserCountsByIdReqDTO;
import com.quanxiaoha.xiaohashu.count.dto.FindUserCountsByIdRspDTO;
import com.quanxiaoha.xiaohashu.count.dto.ReconcileNotePublishCountReqDTO;
import com.quanxiaoha.xiaohashu.count.fallback.CountFeignApiFallback;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 计数服务 Feign 接口
 */
@FeignClient(name = ApiConstants.SERVICE_NAME, fallback = CountFeignApiFallback.class)
public interface CountFeignApi {

    String PREFIX = "/count";

    /**
     * 查询用户计数
     */
    @PostMapping(PREFIX + "/user/data")
    Response<FindUserCountsByIdRspDTO> findUserCount(
            @RequestBody FindUserCountsByIdReqDTO findUserCountsByIdReqDTO);

    /** 批量查询笔记计数 */
    @PostMapping(PREFIX + "/notes/data")
    Response<List<FindNoteCountsByIdRspDTO>> findNotesCount(
            @RequestBody FindNoteCountsByIdsReqDTO findNoteCountsByIdsReqDTO);

    /** Reconcile one user's published-note counter using the shared per-user event lock. */
    @PostMapping(PREFIX + "/user/note-publish/reconcile")
    Response<?> reconcileNotePublishCount(@RequestBody ReconcileNotePublishCountReqDTO request);
}
