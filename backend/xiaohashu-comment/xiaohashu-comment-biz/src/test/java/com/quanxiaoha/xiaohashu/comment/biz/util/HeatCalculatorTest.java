package com.quanxiaoha.xiaohashu.comment.biz.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeatCalculatorTest {

    @Test
    void weightsLikesAtSeventyPercentAndRepliesAtThirtyPercent() {
        assertThat(HeatCalculator.calculateHeat(150, 10)).isEqualByComparingTo("108.00");
    }
}
