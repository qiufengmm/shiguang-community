package com.quanxiaoha.xiaohashu.data.align.rpc;

import com.quanxiaoha.xiaohashu.count.api.CountFeignApi;
import com.quanxiaoha.xiaohashu.count.dto.ReconcileNotePublishCountReqDTO;
import com.quanxiaoha.framework.common.response.Response;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

@Component
public class CountNotePublishReconciliationRpcService {

    @Resource
    private CountFeignApi countFeignApi;

    public boolean reconcile(Long userId) {
        Response<?> response = countFeignApi.reconcileNotePublishCount(
                ReconcileNotePublishCountReqDTO.builder().userId(userId).build());
        return response != null && response.isSuccess();
    }
}
