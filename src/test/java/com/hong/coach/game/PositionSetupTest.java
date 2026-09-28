package com.hong.coach.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 摆棋入口测试：前端交上来的东西（棋子矩阵 / FEN）要能被统一解析成棋盘。
 */
class PositionSetupTest {

    private static final String STANDARD =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR";

    @Test
    @DisplayName("棋子矩阵能建成盘面，走子方用前端指定的那个")
    void buildsFromMatrix() {
        PositionSetup.Built built = PositionSetup.build("t", matrixOf(STANDARD), null, Side.BLACK);

        assertTrue(built.ok(), "不该有问题：" + built.problems());
        assertEquals(Side.BLACK, built.board().getTurn());
        assertEquals(32, built.board().pieceCount());
    }

    @Test
    @DisplayName("矩阵没带走子方时默认红方先行")
    void matrixDefaultsToRed() {
        PositionSetup.Built built = PositionSetup.build("t", matrixOf(STANDARD), null, null);

        assertEquals(Side.RED, built.board().getTurn());
    }

    @Test
    @DisplayName("从 FEN 起摆时，走子方用 FEN 里写的")
    void usesTurnFromFen() {
        PositionSetup.Built built = PositionSetup.build("t", null, STANDARD + " b - - 0 1", null);

        assertTrue(built.ok(), "不该有问题：" + built.problems());
        assertEquals(Side.BLACK, built.board().getTurn());
    }

    @Test
    @DisplayName("矩阵和 FEN 都给时以矩阵为准")
    void matrixWinsOverFen() {
        PositionSetup.Built built = PositionSetup.build("t", matrixOf(STANDARD),
                "4k4/9/9/9/9/9/9/9/9/4K4 w - - 0 1", Side.RED);

        assertEquals(32, built.board().pieceCount(), "应当用的是矩阵那副满盘");
    }

    @Test
    @DisplayName("矩阵行列数不对时给出中文提示，并且不返回盘面")
    void rejectsMalformedMatrix() {
        List<List<String>> tooFewRows = matrixOf(STANDARD);
        tooFewRows.remove(0);
        PositionSetup.Built rows = PositionSetup.build("t", tooFewRows, null, Side.RED);
        assertNull(rows.board());
        assertTrue(rows.problems().get(0).contains("10 行"), rows.problems().toString());

        List<List<String>> shortRow = matrixOf(STANDARD);
        shortRow.get(3).remove(0);
        PositionSetup.Built cols = PositionSetup.build("t", shortRow, null, Side.RED);
        assertNull(cols.board());
        assertTrue(cols.problems().get(0).contains("第 4 行"), cols.problems().toString());
    }

    @Test
    @DisplayName("盘面本身有问题时，照样把棋盘给出来，好让用户在界面上接着改")
    void keepsBoardEvenWhenProblemsFound() {
        PositionSetup.Built built = PositionSetup.build("t", matrixOf("4k4/9/9/9/9/9/9/9/9/4R4"), null, Side.RED);

        assertNotNull(built.board(), "结构没问题就该返回盘面");
        assertFalse(built.ok());
        assertTrue(built.problems().stream().anyMatch(p -> p.contains("红方没有帅")),
                built.problems().toString());
    }

    @Test
    @DisplayName("什么都没给时说清楚缺什么")
    void rejectsEmptyRequest() {
        PositionSetup.Built built = PositionSetup.build("t", null, null, Side.RED);

        assertNull(built.board());
        assertTrue(built.problems().get(0).contains("没有收到盘面"));
    }

    // ==================== 落子之前的判定 ====================

    @Test
    @DisplayName("落子前的判定：违规的摆放挡下来并说明原因，还带着位置")
    void placementBlocksIllegalDrop() {
        // 空盘加两将，红帅已经在 (9,4)；往九宫正中央 (9,4) 换成一枚仕 —— 仕走不到那儿
        PositionSetup.Placement bad = PositionSetup.place("t", matrixOf("4k4/9/9/9/9/9/9/9/9/4K4"), 9, 4, "A");

        assertFalse(bad.ok());
        assertTrue(bad.message().contains("只能落在九宫的"), bad.message());
        assertTrue(bad.details().stream().anyMatch(p -> p.cells().stream()
                        .anyMatch(pos -> pos.r == 9 && pos.c == 4)),
                "出错的格子要带上，界面才圈得出来：" + bad.details());
    }

    @Test
    @DisplayName("落子前的判定：合法的摆放放行，整盘别的毛病不算在它头上")
    void placementAllowsLegalDrop() {
        // 两将照面（整盘不合法），但往 (9,3) 放一枚红仕本身没问题
        List<List<String>> matrix = matrixOf("4k4/9/9/9/9/9/9/9/9/4K4");

        PositionSetup.Placement ok = PositionSetup.place("t", matrix, 9, 3, "A");
        assertTrue(ok.ok(), "不该拦：" + ok.message());
        assertNull(ok.message());

        assertTrue(PositionSetup.build("t", matrix, null, Side.RED).problems().stream()
                        .anyMatch(p -> p.contains("照面")),
                "整盘校验仍然要把照面报出来");
    }

    @Test
    @DisplayName("落子前的判定：第三个红车会被挡下来")
    void placementBlocksThirdRook() {
        PositionSetup.Placement bad = PositionSetup.place("t",
                matrixOf("4k4/9/9/9/9/9/9/4R4/4R4/4K4"), 0, 8, "R");

        assertFalse(bad.ok());
        assertTrue(bad.message().contains("3 个车"), bad.message());
    }

    @Test
    @DisplayName("擦除永远放行；矩阵不对时给出中文提示而不是崩掉")
    void placementHandlesEraseAndBadMatrix() {
        List<List<String>> matrix = matrixOf("4k4/9/9/9/9/9/9/9/9/4K4");

        assertTrue(PositionSetup.place("t", matrix, 9, 4, null).ok(), "擦掉一枚子不该被拦");
        assertTrue(PositionSetup.place("t", matrix, 9, 4, "  ").ok(), "空的棋子码同样当擦除");

        PositionSetup.Placement noTarget = PositionSetup.place("t", matrix, null, null, "A");
        assertFalse(noTarget.ok());
        assertTrue(noTarget.message().contains("落点"), noTarget.message());

        PositionSetup.Placement noMatrix = PositionSetup.place("t", null, 0, 0, "A");
        assertFalse(noMatrix.ok());
        assertTrue(noMatrix.message().contains("盘面"), noMatrix.message());

        List<List<String>> tooShort = matrixOf("4k4/9/9/9/9/9/9/9/9");
        PositionSetup.Placement broken = PositionSetup.place("t", tooShort, 0, 0, "A");
        assertFalse(broken.ok());
        assertTrue(broken.message().contains("10 行"), broken.message());
    }

    // ==================== 辅助 ====================

    /** 把 "rnbakabnr/9/..." 这种紧凑写法转成前端用的 10×9 矩阵 */
    private static List<List<String>> matrixOf(String compact) {
        List<List<String>> matrix = new ArrayList<>();
        for (String row : compact.split("/")) {
            List<String> cells = new ArrayList<>();
            for (char ch : row.toCharArray()) {
                if (ch >= '1' && ch <= '9') {
                    for (int i = 0; i < ch - '0'; i++) cells.add(null);
                } else {
                    cells.add(String.valueOf(ch));
                }
            }
            matrix.add(cells);
        }
        return matrix;
    }
}
