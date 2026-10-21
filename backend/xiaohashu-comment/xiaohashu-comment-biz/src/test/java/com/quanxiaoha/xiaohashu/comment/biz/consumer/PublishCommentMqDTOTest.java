package com.quanxiaoha.xiaohashu.comment.biz.consumer;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.comment.biz.model.dto.PublishCommentMqDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublishCommentMqDTOTest {

    @Test
    void carriesTheSameContentUuidAcrossSerializedRedelivery() {
        PublishCommentMqDTO event = PublishCommentMqDTO.builder()
                .commentId(812L)
                .contentUuid("01680d93-869c-49a7-9f85-6fb4db60364e")
                .content("comment body")
                .build();

        PublishCommentMqDTO firstDelivery = JsonUtils.parseObject(
                JsonUtils.toJsonString(event), PublishCommentMqDTO.class);
        PublishCommentMqDTO retryDelivery = JsonUtils.parseObject(
                JsonUtils.toJsonString(event), PublishCommentMqDTO.class);

        assertThat(firstDelivery.getContentUuid()).isEqualTo(retryDelivery.getContentUuid());
        assertThat(retryDelivery.getContentUuid()).isEqualTo(event.getContentUuid());
    }
}
