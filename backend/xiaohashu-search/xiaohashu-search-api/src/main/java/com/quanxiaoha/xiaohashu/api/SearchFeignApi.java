package com.quanxiaoha.xiaohashu.api;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.constant.ApiConstants;
import com.quanxiaoha.xiaohashu.dto.RebuildNoteDocumentReqDTO;
import com.quanxiaoha.xiaohashu.dto.RebuildUserDocumentReqDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** 搜索服务 Feign 接口 */
@FeignClient(name = ApiConstants.SERVICE_NAME)
public interface SearchFeignApi {

    String PREFIX = "/search";

    @PostMapping(value = PREFIX + "/note/document/rebuild")
    Response<?> rebuildNoteDocument(@RequestBody RebuildNoteDocumentReqDTO request);

    @PostMapping(value = PREFIX + "/user/document/rebuild")
    Response<?> rebuildUserDocument(@RequestBody RebuildUserDocumentReqDTO request);
}
