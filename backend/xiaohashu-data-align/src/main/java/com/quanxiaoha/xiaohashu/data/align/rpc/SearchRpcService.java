package com.quanxiaoha.xiaohashu.data.align.rpc;

import com.quanxiaoha.xiaohashu.api.SearchFeignApi;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.dto.RebuildNoteDocumentReqDTO;
import com.quanxiaoha.xiaohashu.dto.RebuildUserDocumentReqDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/** 搜索服务 RPC 调用封装 */
@Component
public class SearchRpcService {

    @Resource
    private SearchFeignApi searchFeignApi;

    public void rebuildNoteDocument(Long noteId) {
        RebuildNoteDocumentReqDTO request = RebuildNoteDocumentReqDTO.builder()
                .id(noteId)
                .build();
        Response<?> response = searchFeignApi.rebuildNoteDocument(request);
        ensureSuccess(response, "note", noteId);
    }

    public void rebuildUserDocument(Long userId) {
        RebuildUserDocumentReqDTO request = RebuildUserDocumentReqDTO.builder()
                .id(userId)
                .build();
        Response<?> response = searchFeignApi.rebuildUserDocument(request);
        ensureSuccess(response, "user", userId);
    }

    private void ensureSuccess(Response<?> response, String entityType, Long entityId) {
        if (response == null || !response.isSuccess()) {
            String message = response == null ? "empty response" : response.getMessage();
            throw new IllegalStateException("Search service failed to rebuild " + entityType + " document "
                    + entityId + ": " + message);
        }
    }
}
