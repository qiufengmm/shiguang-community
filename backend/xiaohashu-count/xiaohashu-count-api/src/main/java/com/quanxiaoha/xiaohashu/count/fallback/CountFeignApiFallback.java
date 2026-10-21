package com.quanxiaoha.xiaohashu.count.fallback;

import com.google.common.collect.Lists;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.api.CountFeignApi;
import com.quanxiaoha.xiaohashu.count.dto.FindNoteCountsByIdRspDTO;
import com.quanxiaoha.xiaohashu.count.dto.FindNoteCountsByIdsReqDTO;
import com.quanxiaoha.xiaohashu.count.dto.FindUserCountsByIdReqDTO;
import com.quanxiaoha.xiaohashu.count.dto.FindUserCountsByIdRspDTO;
import com.quanxiaoha.xiaohashu.count.dto.ReconcileNotePublishCountReqDTO;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 计数服务降级处理。
 */
@Component
public class CountFeignApiFallback implements CountFeignApi {

    @Override
    public Response<FindUserCountsByIdRspDTO> findUserCount(FindUserCountsByIdReqDTO request) {
        Long userId = request.getUserId();

        return Response.success(FindUserCountsByIdRspDTO.builder()
                .userId(userId)
                .noteTotal(0L)
                .likeTotal(0L)
                .followingTotal(0L)
                .fansTotal(0L)
                .collectTotal(0L)
                .build());
    }

    @Override
    public Response<List<FindNoteCountsByIdRspDTO>> findNotesCount(FindNoteCountsByIdsReqDTO request) {
        List<FindNoteCountsByIdRspDTO> responses = Lists.newArrayList();

        for (Long noteId : request.getNoteIds()) {
            responses.add(FindNoteCountsByIdRspDTO.builder()
                    .noteId(noteId)
                    .collectTotal(0L)
                    .commentTotal(0L)
                    .likeTotal(0L)
                    .build());
        }

        return Response.success(responses);
    }

    @Override
    public Response<?> reconcileNotePublishCount(ReconcileNotePublishCountReqDTO request) {
        return Response.fail("COUNT_RECONCILIATION_UNAVAILABLE", "note publish count reconciliation is unavailable");
    }
}
