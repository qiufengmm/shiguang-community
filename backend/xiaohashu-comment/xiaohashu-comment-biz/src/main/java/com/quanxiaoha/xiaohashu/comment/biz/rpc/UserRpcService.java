package com.quanxiaoha.xiaohashu.comment.biz.rpc;

import cn.hutool.core.collection.CollUtil;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.user.api.UserFeignApi;
import com.quanxiaoha.xiaohashu.user.dto.req.FindUsersByIdsReqDTO;
import com.quanxiaoha.xiaohashu.user.dto.resp.FindUserByIdRspDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class UserRpcService {

    @Resource
    private UserFeignApi userFeignApi;

    public List<FindUserByIdRspDTO> findByIds(List<Long> userIds) {
        if (CollUtil.isEmpty(userIds)) {
            return null;
        }

        FindUsersByIdsReqDTO request = new FindUsersByIdsReqDTO();
        request.setIds(userIds.stream().distinct().collect(Collectors.toList()));
        Response<List<FindUserByIdRspDTO>> response = userFeignApi.findByIds(request);
        if (Objects.isNull(response) || !response.isSuccess()
                || Objects.isNull(response.getData()) || CollUtil.isEmpty(response.getData())) {
            return null;
        }
        return response.getData();
    }
}
