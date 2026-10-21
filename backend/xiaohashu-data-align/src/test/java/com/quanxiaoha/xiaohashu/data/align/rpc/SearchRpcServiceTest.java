package com.quanxiaoha.xiaohashu.data.align.rpc;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.api.SearchFeignApi;
import com.quanxiaoha.xiaohashu.dto.RebuildNoteDocumentReqDTO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchRpcServiceTest {

    @Test
    void rejectsBusinessFailureReturnedBySearchService() {
        SearchFeignApi api = mock(SearchFeignApi.class);
        when(api.rebuildNoteDocument(any(RebuildNoteDocumentReqDTO.class)))
                .thenReturn(Response.fail("search rebuild failed"));
        SearchRpcService service = new SearchRpcService();
        ReflectionTestUtils.setField(service, "searchFeignApi", api);

        assertThatThrownBy(() -> service.rebuildNoteDocument(123456L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("search rebuild failed");
    }
}
