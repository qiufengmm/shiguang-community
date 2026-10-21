package com.quanxiaoha.xiaohashu.kv.api;

import com.quanxiaoha.xiaohashu.kv.constant.ApiConstants;
import com.quanxiaoha.xiaohashu.kv.dto.req.AddNoteContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.BatchFindCommentContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.DeleteNoteContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.FindNoteContentReqDTO;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class KeyValueFeignApiContractTest {

    @Test
    void addsNoteContentThroughTheKvServiceEndpoint() throws Exception {
        FeignClient client = KeyValueFeignApi.class.getAnnotation(FeignClient.class);
        PostMapping mapping = KeyValueFeignApi.class
                .getMethod("addNoteContent", AddNoteContentReqDTO.class)
                .getAnnotation(PostMapping.class);
        RequestBody requestBody = KeyValueFeignApi.class
                .getMethod("addNoteContent", AddNoteContentReqDTO.class)
                .getParameters()[0].getAnnotation(RequestBody.class);

        assertNotNull(client);
        assertEquals(ApiConstants.SERVICE_NAME, client.name());
        assertEquals("/kv/note/content/add", mapping.value()[0]);
        assertNotNull(requestBody);
    }

    @Test
    void findsNoteContentThroughTheKvServiceEndpoint() throws Exception {
        PostMapping mapping = KeyValueFeignApi.class
                .getMethod("findNoteContent", FindNoteContentReqDTO.class)
                .getAnnotation(PostMapping.class);
        RequestBody requestBody = KeyValueFeignApi.class
                .getMethod("findNoteContent", FindNoteContentReqDTO.class)
                .getParameters()[0].getAnnotation(RequestBody.class);

        assertEquals("/kv/note/content/find", mapping.value()[0]);
        assertNotNull(requestBody);
    }

    @Test
    void deletesNoteContentThroughTheKvServiceEndpoint() throws Exception {
        PostMapping mapping = KeyValueFeignApi.class
                .getMethod("deleteNoteContent", DeleteNoteContentReqDTO.class)
                .getAnnotation(PostMapping.class);
        RequestBody requestBody = KeyValueFeignApi.class
                .getMethod("deleteNoteContent", DeleteNoteContentReqDTO.class)
                .getParameters()[0].getAnnotation(RequestBody.class);

        assertEquals("/kv/note/content/delete", mapping.value()[0]);
        assertNotNull(requestBody);
    }

    @Test
    void findsCommentContentThroughTheKvServiceEndpoint() throws Exception {
        PostMapping mapping = KeyValueFeignApi.class
                .getMethod("batchFindCommentContent", BatchFindCommentContentReqDTO.class)
                .getAnnotation(PostMapping.class);
        RequestBody requestBody = KeyValueFeignApi.class
                .getMethod("batchFindCommentContent", BatchFindCommentContentReqDTO.class)
                .getParameters()[0].getAnnotation(RequestBody.class);

        assertEquals("/kv/comment/content/batchFind", mapping.value()[0]);
        assertNotNull(requestBody);
    }

    @Test
    void deletesCommentContentThroughTheKvServiceEndpoint() {
        Method method = Arrays.stream(KeyValueFeignApi.class.getMethods())
                .filter(candidate -> candidate.getName().equals("deleteCommentContent"))
                .findFirst()
                .orElse(null);

        assertNotNull(method);
        assertEquals("DeleteCommentContentReqDTO", method.getParameterTypes()[0].getSimpleName());
        PostMapping mapping = method.getAnnotation(PostMapping.class);
        RequestBody requestBody = method.getParameters()[0].getAnnotation(RequestBody.class);
        assertEquals("/kv/comment/content/delete", mapping.value()[0]);
        assertNotNull(requestBody);
    }
}
