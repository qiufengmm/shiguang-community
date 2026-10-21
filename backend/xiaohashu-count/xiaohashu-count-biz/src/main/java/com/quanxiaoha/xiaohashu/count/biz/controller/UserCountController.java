package com.quanxiaoha.xiaohashu.count.biz.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.quanxiaoha.framework.biz.operationlog.aspect.ApiOperationLog;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.count.biz.service.UserCountService;
import com.quanxiaoha.xiaohashu.count.biz.service.CountNotePublishReconciliationService;
import com.quanxiaoha.xiaohashu.count.dto.FindUserCountsByIdReqDTO;
import com.quanxiaoha.xiaohashu.count.dto.FindUserCountsByIdRspDTO;
import com.quanxiaoha.xiaohashu.count.dto.ReconcileNotePublishCountReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户维度计数
 **/
@RestController
@RequestMapping("/count")
@Slf4j
public class UserCountController {

    @Value("${tutorial.sentinel.failure-demo-enabled:true}")
    private boolean failureDemoEnabled;

    @Resource
    private UserCountService userCountService;

    @Resource
    private CountNotePublishReconciliationService notePublishReconciliationService;

    @PostMapping(value = "/user/data")
    @ApiOperationLog(description = "获取用户计数数据")
    @SentinelResource(value = "findUserCountData4Controller", blockHandler = "blockHandler4findUserCountData")
    public Response<FindUserCountsByIdRspDTO> findUserCountData(
            @Validated @RequestBody FindUserCountsByIdReqDTO findUserCountsByIdReqDTO) {
        // 模拟接口随机发生异常，抛出概率约为 50%。
        if (failureDemoEnabled && Math.random() > 0.5) {
            throw new RuntimeException();
        }
        return userCountService.findUserCountData(findUserCountsByIdReqDTO);
    }

    /**
     * 测试 “流控模式：链路” 使用
     */
    @PostMapping(value = "/user/data2")
    @ApiOperationLog(description = "获取用户计数数据")
    @SentinelResource("findUserCountData4Controller2")
    public Response<FindUserCountsByIdRspDTO> findUserCountData2(
            @Validated @RequestBody FindUserCountsByIdReqDTO findUserCountsByIdReqDTO) {
        return userCountService.findUserCountData(findUserCountsByIdReqDTO);
    }

    @PostMapping("/user/note-publish/reconcile")
    public Response<?> reconcileNotePublishCount(
            @Validated @RequestBody ReconcileNotePublishCountReqDTO request) {
        return Response.success(notePublishReconciliationService.reconcile(request.getUserId()));
    }

    /**
     * Controller 层限流后的兜底处理
     */
    public Response<FindUserCountsByIdRspDTO> blockHandler4findUserCountData(
            FindUserCountsByIdReqDTO findUserCountsByIdReqDTO, BlockException blockException) {
        log.warn("## /count/user/count 接口被限流: {}", JsonUtils.toJsonString(findUserCountsByIdReqDTO));

        return Response.success(FindUserCountsByIdRspDTO.builder()
                .userId(findUserCountsByIdReqDTO.getUserId())
                .collectTotal(0L)
                .fansTotal(0L)
                .followingTotal(0L)
                .likeTotal(0L)
                .noteTotal(0L)
                .build());
    }
}
