package com.hong.coach.game;

import com.hong.coach.game.XqRules.PieceType;
import com.hong.coach.game.XqRules.Pos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 局面解析与校验测试。
 * <p>
 * 摆棋功能的地基：FEN 要能双向转换，摆歪了的局面要在开局之前就被指出来。
 */
class FenParserTest {

    private static final String STANDARD =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";

    // ==================== 解析 ====================

    @Test
    @DisplayName("标准开局 FEN 能解析出满盘三十二子，且没有任何问题")
    void parsesStandardOpening() {
        Board board = FenParser.parse("t", STANDARD);

        assertEquals(32, board.pieceCount());
        assertEquals(Side.RED, board.getTurn());
        assertTrue(FenParser.problems(board).isEmpty(), "标准开局必须被判定为可下");
    }

    @Test
    @DisplayName("棋子的种类与红黑都摆对了")
    void piecesArePlacedCorrectly() {
        Board board = FenParser.parse("t", STANDARD);

        assertEquals(PieceType.ROOK, board.at(0, 0).type);
        assertEquals(Side.BLACK, board.at(0, 0).side, "小写是黑方");
        assertEquals(PieceType.GENERAL, board.at(9, 4).type);
        assertEquals(Side.RED, board.at(9, 4).side, "大写是红方");
        assertEquals(PieceType.CANNON, board.at(2, 1).type);
        assertEquals(PieceType.PAWN, board.at(6, 8).type);
        assertNull(board.at(4, 4), "河界中间应当是空的");
    }

    @Test
    @DisplayName("写出 FEN 再读回来是同一个局面（往返一致）")
    void writeThenParseIsStable() {
        Board board = FenParser.parse("t", "3ak4/9/9/9/9/6P2/4N4/9/4R4/4K4 b - - 0 1");

        Board again = FenParser.parse("t2", board.getFen());

        assertEquals(board.getFen(), again.getFen());
        assertEquals(Side.BLACK, again.getTurn());
        assertEquals(6, again.pieceCount());
    }

    @Test
    @DisplayName("只给盘面不给走子方时，默认红方先行")
    void defaultsToRedWhenTurnMissing() {
        Board board = FenParser.parse("t", "4k4/9/9/9/9/9/9/9/9/4K4");

        assertEquals(Side.RED, board.getTurn());
    }

    @Test
    @DisplayName("结构不对的 FEN 给出能直接给用户看的中文提示")
    void rejectsBrokenFen() {
        assertTrue(failureOf("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9").contains("10 行"));
        assertTrue(failureOf("rnbakabnz/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR")
                .contains("不认识的符号"));
        assertTrue(failureOf("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABN")
                .contains("9 列"));
        assertTrue(failureOf("rnbakabnrr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR")
                .contains("超过"));
        assertTrue(failureOf("   ").contains("空的"));
    }

    // ==================== 局面校验 ====================

    @Test
    @DisplayName("缺将帅、将帅不在九宫都会被拦下来，并且说清楚该待在哪")
    void rejectsMissingOrDisplacedGeneral() {
        assertContains(problemsOf("4k4/9/9/9/9/9/9/9/9/4R4 w - - 0 1"), "红方没有帅");
        // 黑将跑到红方那半边的九宫里：确实在「一个」九宫里，但那是别人的九宫
        assertContains(problemsOf("9/9/9/9/9/9/9/9/3k5/4K4 w - - 0 1"), "黑方的将摆在了红方的九宫里");
        assertContains(problemsOf("9/9/9/9/9/9/9/9/3k5/4K4 w - - 0 1"), "第 1~3 行第 4~6 列");
        // 干脆摆在九宫外
        assertContains(problemsOf("9/9/9/9/9/9/9/9/9/k8 w - - 0 1"), "黑方的将不在九宫里");
    }

    @Test
    @DisplayName("局面文本的行序写反了（红帅写到第 1 行）要说清楚是哪个九宫的问题")
    void explainsGeneralSittingInOpponentPalace() {
        // 这份局面文本是按「第 1 行＝红方底线」的想当然写法来的：
        // 红帅落在第 1 行，那是黑方的九宫；黑将落在第 10 行，那是红方的九宫
        List<String> problems = problemsOf("4K4/9/9/9/4R4/3r5/9/9/9/3k5 w - - 0 1");

        assertContains(problems, "红方的帅摆在了黑方的九宫里");
        assertContains(problems, "帅只能待在自己这半边的九宫，也就是 第 8~10 行第 4~6 列");
        assertContains(problems, "黑方的将摆在了红方的九宫里");
        assertContains(problems, "将只能待在自己这半边的九宫，也就是 第 1~3 行第 4~6 列");
        // 这两条提示都得带上具体位置，界面才圈得出该挪哪个子
        assertEquals(2, problems.size(), problems.toString());
    }

    @Test
    @DisplayName("仕相越界、兵卒后退都会被拦下来")
    void rejectsPiecesOutsideTheirTerritory() {
        // 红相跑到黑方半场（第 4 行），顺手挡在两将之间，避免同时报「照面」
        assertContains(problemsOf("4k4/9/9/4B4/9/9/9/9/9/4K4 w - - 0 1"), "相不能过河");
        // 红兵在第 8 行——红兵只能向前（向上）走，退不回来
        assertContains(problemsOf("4k4/9/9/9/9/9/9/4P4/9/4K4 w - - 0 1"), "红兵摆不了");
        // 黑卒在第 2 行同理
        assertContains(problemsOf("4k4/4p4/9/9/9/9/9/9/9/4K4 b - - 0 1"), "黑卒摆不了");
    }

    @Test
    @DisplayName("两将照面会被拦下来")
    void rejectsFacingGenerals() {
        assertContains(problemsOf("4k4/9/9/9/9/9/9/9/9/4K4 w - - 0 1"), "照面");
    }

    @Test
    @DisplayName("轮到的一方不可能让对手站在原地被将军 —— 这种局面接不上")
    void rejectsPositionWhereOpponentIsInCheck() {
        // 轮到红方走，红车却已经照着黑将，说明黑方上一手留下了自己被将的棋
        assertContains(problemsOf("4k4/9/9/9/9/9/9/9/4R4/4K4 w - - 0 1"), "正被将军");
    }

    @Test
    @DisplayName("轮到的一方已经被将死 / 困毙时会提示换个局面")
    void rejectsDeadPosition() {
        // 黑将被红方两个车封在九宫里，轮黑走时已经无路可逃
        List<String> problems = problemsOf("R3k4/R8/9/9/9/9/4P4/9/9/4K4 b - - 0 1");

        assertContains(problems, "已经无棋可走");
    }

    @Test
    @DisplayName("棋子数量超过上限会被指出来")
    void rejectsTooManyPieces() {
        assertContains(problemsOf("4k4/9/9/9/9/9/4R4/4R4/4R4/4K4 w - - 0 1"), "3 个车");
    }

    @Test
    @DisplayName("仕只能落在九宫的斜线点上（九宫正中也不行）")
    void rejectsAdvisorOffDiagonalPoints() {
        // 红仕摆在九宫正中 e0(第 10 行第 5 列)：在九宫内，但没有哪个仕走得到那里
        assertContains(problemsOf("4k4/9/9/9/9/9/9/9/9/RNBKA1BNR w - - 0 1"), "只能落在九宫的");
        // 黑士摆到九宫外
        assertContains(problemsOf("A3k4/9/9/9/9/9/9/9/9/4K4 w - - 0 1"), "只能待在九宫里");
    }

    @Test
    @DisplayName("相只能落在己方半场的 7 个田字点上")
    void rejectsElephantOffPoints() {
        // 红相在 (8,6)：没过河，但不是相的点位（相的落点只有七个）
        assertContains(problemsOf("4k4/9/9/9/9/9/9/9/6B2/RNBAKA1NR w - - 0 1"), "只能落在己方半场的");
        // 黑象在 (1,6)：同样在己方半场却不是点位
        assertContains(problemsOf("3ak4/6b2/9/9/9/9/9/9/9/3K5 b - - 0 1"), "只能落在己方半场的");
    }

    @Test
    @DisplayName("正规的仕相落点不会被误判")
    void acceptsLegitAdvisorAndElephantPoints() {
        // 黑士在 (0,5)、黑象在 (4,2)；红仕在 (9,3)、红相在 (7,4) —— 都是各自合法的点
        List<String> problems = problemsOf("3k1a3/9/9/9/2b6/9/9/4B4/9/3AK4 b - - 0 1");

        assertTrue(problems.isEmpty(), "不该报问题，实际：" + problems);
    }

    @Test
    @DisplayName("校验结果带着出错的位置，界面才能把格子圈出来")
    void problemsCarryOffendingCells() {
        List<FenParser.Problem> details = FenParser.diagnose(
                FenParser.parse("t", "4k4/9/9/9/9/9/9/9/6B2/RNBAKA1NR w - - 0 1"));

        assertFalse(details.isEmpty(), "这个局面应当有问题");
        assertTrue(details.stream().anyMatch(p -> p.cells().stream()
                        .anyMatch(pos -> pos.r == 8 && pos.c == 6)),
                "摆错位置的相（第 9 行第 7 列）应当被标出来，实际：" + details);
        // 同一个位置只报一次，界面画出的是一个圈而不是两个
        long atPoint = details.stream().filter(p -> p.cells().stream()
                .anyMatch(pos -> pos.r == 8 && pos.c == 6)).count();
        assertEquals(1, atPoint);
    }

    @Test
    @DisplayName("一个正常的残局不会报任何问题")
    void acceptsLegitEndgame() {
        // 红方单车单马，黑方光将，轮到黑方走
        List<String> problems = problemsOf("3ak4/9/9/9/9/9/4N4/9/4R4/4K4 b - - 0 1");

        assertTrue(problems.isEmpty(), "不该报问题，实际：" + problems);
    }

    // ==================== 落子之前的判定 ====================

    @Test
    @DisplayName("落子前的判定不拿整盘的毛病拦人，只管这一枚子")
    void placementCheckIgnoresWholeBoardIssues() {
        // 这个局面整体是坏的：红车照着黑将，轮到红方走根本接不上
        Board board = FenParser.parse("t", "4k4/9/9/9/9/9/9/9/4R4/4K4 w - - 0 1");
        board.set(5, 4, XqRules.create(PieceType.ROOK, Side.RED, new Pos(5, 4)));

        assertTrue(FenParser.problemsAt(board, new Pos(5, 4)).isEmpty(),
                "往中间摆一枚车本身没毛病，不该在落子之前就被挡住");
        assertFalse(FenParser.problems(board).isEmpty(), "整盘校验照样要把毛病报出来");
    }

    @Test
    @DisplayName("落子前的判定管得住数量、九宫点位与兵卒方向")
    void placementCheckCatchesLocalViolations() {
        // 第三个红车
        Board threeRooks = FenParser.parse("t", "4k4/9/9/9/9/9/9/4R4/4R4/4K4 b - - 0 1");
        threeRooks.set(0, 8, XqRules.create(PieceType.ROOK, Side.RED, new Pos(0, 8)));
        assertContains(placeTexts(threeRooks, 0, 8), "3 个车");

        // 红仕摆在九宫正中（第 10 行第 5 列）：在九宫里，但仕走斜线，到不了那儿
        Board advisor = FenParser.parse("t", "4k4/9/9/9/9/9/9/9/9/4K4 b - - 0 1");
        advisor.set(9, 4, XqRules.create(PieceType.ADVISOR, Side.RED, new Pos(9, 4)));
        assertContains(placeTexts(advisor, 9, 4), "只能落在九宫的");

        // 红帅摆在黑方九宫里
        Board general = FenParser.parse("t", "4k4/9/9/9/9/9/9/9/9/4K4 b - - 0 1");
        general.set(1, 4, XqRules.create(PieceType.GENERAL, Side.RED, new Pos(1, 4)));
        assertContains(placeTexts(general, 1, 4), "摆在了黑方的九宫里");

        // 红兵退到第 9 行
        Board pawn = FenParser.parse("t", "4k4/9/9/9/9/9/9/9/9/4K4 b - - 0 1");
        pawn.set(8, 4, XqRules.create(PieceType.PAWN, Side.RED, new Pos(8, 4)));
        assertContains(placeTexts(pawn, 8, 4), "红兵摆不了");
    }

    @Test
    @DisplayName("落子前的判定只看落点那一格：同一枚子换个点就放得下了")
    void placementCheckIsCellSpecific() {
        Board board = FenParser.parse("t", "4k4/9/9/9/9/9/9/9/9/4K4 b - - 0 1");
        board.set(9, 4, XqRules.create(PieceType.ADVISOR, Side.RED, new Pos(9, 4)));

        assertFalse(FenParser.problemsAt(board, new Pos(9, 4)).isEmpty(), "九宫正中摆不了");
        // 同一枚仕挪到九宫左上角（第 10 行第 4 列）就是它该在的地方
        board.set(9, 4, null);
        board.set(9, 3, XqRules.create(PieceType.ADVISOR, Side.RED, new Pos(9, 3)));
        assertTrue(FenParser.problemsAt(board, new Pos(9, 3)).isEmpty(),
                "九宫左上角是仕的合法落点");
    }

    // ==================== 辅助 ====================

    private static List<String> problemsOf(String fen) {
        return FenParser.problems(FenParser.parse("t", fen));
    }

    /** 落子之前的判定结果（纯文本） */
    private static List<String> placeTexts(Board board, int r, int c) {
        return FenParser.problemsAt(board, new Pos(r, c)).stream().map(FenParser.Problem::text).toList();
    }

    private static void assertContains(List<String> problems, String fragment) {
        assertTrue(problems.stream().anyMatch(p -> p.contains(fragment)),
                "期望有一个问题包含「" + fragment + "」，实际：" + problems);
    }

    private static String failureOf(String fen) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FenParser.parse("t", fen));
        return e.getMessage();
    }
}
