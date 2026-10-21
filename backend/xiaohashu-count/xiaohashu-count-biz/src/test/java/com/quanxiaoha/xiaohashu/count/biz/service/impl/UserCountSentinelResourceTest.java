package com.quanxiaoha.xiaohashu.count.biz.service.impl;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.dto.FindUserCountsByIdReqDTO;
import com.quanxiaoha.xiaohashu.count.dto.FindUserCountsByIdRspDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserCountSentinelResourceTest {

    @Test
    void findUserCountDataDeclaresSentinelResourceAndBlockHandler() throws Exception {
        Method method = UserCountServiceImpl.class.getMethod("findUserCountData", FindUserCountsByIdReqDTO.class);

        SentinelResource sentinelResource = method.getAnnotation(SentinelResource.class);

        assertNotNull(sentinelResource);
        assertEquals("findUserCountData", sentinelResource.value());
        assertEquals("blockHandler4findUserCountData", sentinelResource.blockHandler());
    }

    @Test
    void blockHandlerReturnsSuccessfulResponseWithZeroCounts() throws Exception {
        Method method = Arrays.stream(UserCountServiceImpl.class.getMethods())
                .filter(candidate -> candidate.getName().equals("blockHandler4findUserCountData"))
                .findFirst()
                .orElse(null);
        assertNotNull(method);

        Long userId = 971234567890124L;
        UserCountServiceImpl service = new UserCountServiceImpl();
        @SuppressWarnings("unchecked")
        Response<FindUserCountsByIdRspDTO> response = (Response<FindUserCountsByIdRspDTO>) method.invoke(
                service, FindUserCountsByIdReqDTO.builder().userId(userId).build(), (BlockException) null);

        assertTrue(response.isSuccess());
        assertEquals(userId, response.getData().getUserId());
        assertEquals(0L, response.getData().getFansTotal());
        assertEquals(0L, response.getData().getFollowingTotal());
        assertEquals(0L, response.getData().getNoteTotal());
        assertEquals(0L, response.getData().getLikeTotal());
        assertEquals(0L, response.getData().getCollectTotal());
    }
}
