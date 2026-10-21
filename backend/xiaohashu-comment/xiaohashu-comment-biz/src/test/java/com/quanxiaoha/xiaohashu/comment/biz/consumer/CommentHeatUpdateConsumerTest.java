package com.quanxiaoha.xiaohashu.comment.biz.consumer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CommentHeatUpdateConsumerTest {

    @Test
    void mergesAndDeduplicatesCommentIdsAcrossBodies() throws Exception {
        Set<Long> commentIds = CommentHeatUpdateConsumer.collectCommentIds(List.of("[11,12,11]", "[12,13]"));

        assertThat(commentIds).containsExactlyInAnyOrder(11L, 12L, 13L);
    }
}
