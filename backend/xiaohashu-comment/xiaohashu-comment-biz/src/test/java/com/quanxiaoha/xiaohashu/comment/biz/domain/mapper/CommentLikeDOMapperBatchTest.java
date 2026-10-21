package com.quanxiaoha.xiaohashu.comment.biz.domain.mapper;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CommentLikeDOMapperBatchTest {

    @Test
    void declaresBatchInsertAndBatchDeleteOperations() {
        Set<String> methodNames = Arrays.stream(CommentLikeDOMapper.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .collect(Collectors.toSet());

        assertThat(methodNames).contains("batchInsert", "batchDelete");
    }
}
