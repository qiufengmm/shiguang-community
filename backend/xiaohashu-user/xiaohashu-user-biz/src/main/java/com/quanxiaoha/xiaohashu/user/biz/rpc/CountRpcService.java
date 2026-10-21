package com.quanxiaoha.xiaohashu.user.biz.rpc;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.api.CountFeignApi;
import com.quanxiaoha.xiaohashu.count.dto.FindUserCountsByIdReqDTO;
import com.quanxiaoha.xiaohashu.count.dto.FindUserCountsByIdRspDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 计数服务远程调用
 */
@Component
public class CountRpcService {

    @Resource
    private CountFeignApi countFeignApi;

    public FindUserCountsByIdRspDTO findUserCountById(Long userId) {
        FindUserCountsByIdReqDTO request = new FindUserCountsByIdReqDTO();
        request.setUserId(userId);

        Response<FindUserCountsByIdRspDTO> response = countFeignApi.findUserCount(request);
        if (Objects.isNull(response) || !response.isSuccess()) {
            return null;
        }
        return response.getData();
    }
}
