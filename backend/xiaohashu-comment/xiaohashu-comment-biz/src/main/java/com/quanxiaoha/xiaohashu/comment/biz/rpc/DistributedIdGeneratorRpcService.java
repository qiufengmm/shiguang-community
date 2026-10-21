package com.quanxiaoha.xiaohashu.comment.biz.rpc;

import com.quanxiaoha.xiaohashu.distributed.id.generator.api.DistributedIdGeneratorFeignApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

@Component
public class DistributedIdGeneratorRpcService {

    @Resource
    private DistributedIdGeneratorFeignApi distributedIdGeneratorFeignApi;

    public String generateCommentId() {
        return distributedIdGeneratorFeignApi.getSegmentId("leaf-segment-comment-id");
    }
}
