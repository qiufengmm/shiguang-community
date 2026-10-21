package com.quanxiaoha.xiaohashu.comment.biz.rpc;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.comment.biz.model.bo.CommentBO;
import com.quanxiaoha.xiaohashu.kv.api.KeyValueFeignApi;
import com.quanxiaoha.xiaohashu.kv.dto.req.BatchAddCommentContentReqDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KeyValueRpcServiceTest {

    @Test
    void sendsCommentTextWithNoteMonthAndContentId() {
        KeyValueFeignApi keyValueFeignApi = mock(KeyValueFeignApi.class);
        when(keyValueFeignApi.batchAddCommentContent(any())).thenReturn(Response.success());
        KeyValueRpcService rpcService = new KeyValueRpcService();
        ReflectionTestUtils.setField(rpcService, "keyValueFeignApi", keyValueFeignApi);

        UUID contentId = UUID.randomUUID();
        CommentBO comment = CommentBO.builder()
                .noteId(123L)
                .contentUuid(contentId.toString())
                .content("batch stored comment")
                .createTime(LocalDateTime.of(2026, 10, 16, 12, 30))
                .build();

        assertThat(rpcService.batchAddCommentContents(List.of(comment))).isTrue();

        ArgumentCaptor<BatchAddCommentContentReqDTO> requestCaptor =
                ArgumentCaptor.forClass(BatchAddCommentContentReqDTO.class);
        verify(keyValueFeignApi).batchAddCommentContent(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getComments()).hasSize(1);
        assertThat(requestCaptor.getValue().getComments().get(0).getNoteId()).isEqualTo(123L);
        assertThat(requestCaptor.getValue().getComments().get(0).getYearMonth()).isEqualTo("2026-10");
        assertThat(requestCaptor.getValue().getComments().get(0).getContentId()).isEqualTo(contentId.toString());
        assertThat(requestCaptor.getValue().getComments().get(0).getContent()).isEqualTo("batch stored comment");
    }
}
