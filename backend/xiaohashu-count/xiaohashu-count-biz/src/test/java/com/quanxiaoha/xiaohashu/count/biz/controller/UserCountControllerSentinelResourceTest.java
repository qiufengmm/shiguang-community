package com.quanxiaoha.xiaohashu.count.biz.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.dto.FindUserCountsByIdReqDTO;
import com.quanxiaoha.xiaohashu.count.dto.FindUserCountsByIdRspDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserCountControllerSentinelResourceTest {

    @Test
    void endpointDeclaresControllerSentinelResourceAndBlockHandler() throws Exception {
        Method method = UserCountController.class.getMethod(
                "findUserCountData", FindUserCountsByIdReqDTO.class);

        SentinelResource sentinelResource = method.getAnnotation(SentinelResource.class);

        assertNotNull(sentinelResource);
        assertEquals("findUserCountData4Controller", sentinelResource.value());
        assertEquals("blockHandler4findUserCountData", sentinelResource.blockHandler());
    }

    @Test
    void controllerBlockHandlerReturnsSuccessfulResponseWithZeroCounts() throws Exception {
        Long userId = 972345678901234L;
        UserCountController controller = new UserCountController();
        Method method = UserCountController.class.getMethod(
                "blockHandler4findUserCountData", FindUserCountsByIdReqDTO.class, BlockException.class);

        @SuppressWarnings("unchecked")
        Response<FindUserCountsByIdRspDTO> response = (Response<FindUserCountsByIdRspDTO>) method.invoke(
                controller, FindUserCountsByIdReqDTO.builder().userId(userId).build(), (BlockException) null);

        assertTrue(response.isSuccess());
        assertEquals(userId, response.getData().getUserId());
        assertEquals(0L, response.getData().getFansTotal());
        assertEquals(0L, response.getData().getFollowingTotal());
        assertEquals(0L, response.getData().getNoteTotal());
        assertEquals(0L, response.getData().getLikeTotal());
        assertEquals(0L, response.getData().getCollectTotal());
    }
}
