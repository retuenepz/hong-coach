package com.hong.coach.service;

import com.hong.coach.game.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 成色判定的阈值测试。
 * <p>
 * 这几个数字直接决定「教练什么时候开口」，改错了会造成要么鸦雀无声、
 * 要么每步棋都在唠叨，因此单独钉住。
 */
class MoveReviewTest {

    @Test
    @DisplayName("按评分落差给出成色，小于 0.4 个兵不提示")
    void qualityThresholds() {
        assertNull(MoveReview.qualityOf(0));
        assertNull(MoveReview.qualityOf(39));
        assertEquals("稍欠精确", MoveReview.qualityOf(40));
        assertEquals("失误", MoveReview.qualityOf(80));
        assertEquals("漏着", MoveReview.qualityOf(200));
        assertEquals("严重漏着", MoveReview.qualityOf(400));
    }

    @Test
    @DisplayName("主变为空时不会变成 null，避免下游空指针")
    void nullPrincipalVariationBecomesEmpty() {
        MoveReview review = new MoveReview(Side.RED, "炮二平五", "h2e2", 0, 0, 0, null, null, null, null);
        assertEquals(0, review.pvChinese().size());
    }
}
