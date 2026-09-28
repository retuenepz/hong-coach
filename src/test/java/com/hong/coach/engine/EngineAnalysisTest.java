package com.hong.coach.engine;

import com.hong.coach.game.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评分视角换算的单元测试。
 * <p>
 * 引擎给出的评分是「行棋方视角」，而教练要用的是「用户视角」，
 * 这里把换算规则钉死，避免出现「走了臭棋评分反而变好」这种符号错误。
 */
class EngineAnalysisTest {

    private static EngineAnalysis analysis(String sideToMove, int cp) {
        EngineAnalysis a = new EngineAnalysis();
        a.setFen("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR " + sideToMove + " - - 0 1");
        a.setScoreCp(cp);
        return a;
    }

    @Test
    @DisplayName("轮到红方走时，评分就是红方视角")
    void redToMove() {
        EngineAnalysis a = analysis("w", 50);
        assertEquals(50, a.evalCpFor(Side.RED));
        assertEquals(-50, a.evalCpFor(Side.BLACK));
    }

    @Test
    @DisplayName("轮到黑方走时，评分是黑方视角，换算到红方要取反")
    void blackToMove() {
        EngineAnalysis a = analysis("b", 50);
        assertEquals(-50, a.evalCpFor(Side.RED));
        assertEquals(50, a.evalCpFor(Side.BLACK));
    }

    @Test
    @DisplayName("红方走了一步臭棋，评分落差应当为正")
    void evalLossIsPositiveForBadMove() {
        // 走之前轮到红方，红方略微占优
        EngineAnalysis before = analysis("w", 13);
        // 走之后轮到黑方，黑方已经领先
        EngineAnalysis after = analysis("b", 163);

        int evalBefore = before.evalCpFor(Side.RED);
        int evalAfter = after.evalCpFor(Side.RED);
        int loss = evalBefore - evalAfter;

        assertEquals(13, evalBefore);
        assertEquals(-163, evalAfter);
        assertEquals(176, loss);
        assertTrue(loss > 0, "走了亏棋，评分落差必须为正");
    }

    @Test
    @DisplayName("杀棋评分会被折算成很大的分值")
    void mateScoreIsConverted() {
        EngineAnalysis mate = new EngineAnalysis();
        mate.setFen("x b - - 0 1");
        mate.setScoreMate(-3);
        assertTrue(mate.isMate());
        assertTrue(mate.evalCpFor(Side.BLACK) < -9000);
        assertTrue(mate.evalCpFor(Side.RED) > 9000);
    }
}
