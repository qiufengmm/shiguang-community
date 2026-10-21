package com.quanxiaoha.framework.common.util;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JsonUtilsTest {

    @Test
    void parsesJsonArraysAsDeduplicatedSets() throws Exception {
        Set<Long> values = JsonUtils.parseSet("[3,1,3]", Long.class);

        assertThat(values).containsExactlyInAnyOrder(1L, 3L);
    }
}
