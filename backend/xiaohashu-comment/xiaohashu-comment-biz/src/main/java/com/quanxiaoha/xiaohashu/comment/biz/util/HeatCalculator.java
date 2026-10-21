package com.quanxiaoha.xiaohashu.comment.biz.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 评论热度值计算。 */
public class HeatCalculator {
    private static final double LIKE_WEIGHT = 0.7;
    private static final double REPLY_WEIGHT = 0.3;

    public static BigDecimal calculateHeat(long likeCount, long replyCount) {
        BigDecimal likeWeight = new BigDecimal(LIKE_WEIGHT);
        BigDecimal replyWeight = new BigDecimal(REPLY_WEIGHT);
        BigDecimal likeCountBD = new BigDecimal(likeCount);
        BigDecimal replyCountBD = new BigDecimal(replyCount);
        BigDecimal heat = likeCountBD.multiply(likeWeight).add(replyCountBD.multiply(replyWeight));
        return heat.setScale(2, RoundingMode.HALF_UP);
    }
}
