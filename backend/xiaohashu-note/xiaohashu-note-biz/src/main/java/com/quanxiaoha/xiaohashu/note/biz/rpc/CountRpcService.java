package com.quanxiaoha.xiaohashu.note.biz.rpc;

import cn.hutool.core.collection.CollUtil;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.api.CountFeignApi;
import com.quanxiaoha.xiaohashu.count.dto.FindNoteCountsByIdRspDTO;
import com.quanxiaoha.xiaohashu.count.dto.FindNoteCountsByIdsReqDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 计数服务 RPC 调用
 */
@Component
public class CountRpcService {

    @Resource
    private CountFeignApi countFeignApi;

    /** 批量查询笔记计数 */
    public List<FindNoteCountsByIdRspDTO> findByNoteIds(List<Long> noteIds) {
        FindNoteCountsByIdsReqDTO request = new FindNoteCountsByIdsReqDTO();
        request.setNoteIds(noteIds);

        Response<List<FindNoteCountsByIdRspDTO>> response = countFeignApi.findNotesCount(request);
        if (Objects.isNull(response) || !response.isSuccess()
                || Objects.isNull(response.getData()) || CollUtil.isEmpty(response.getData())) {
            return null;
        }
        return response.getData();
    }
}
